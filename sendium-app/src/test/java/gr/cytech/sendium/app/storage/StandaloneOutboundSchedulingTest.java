package gr.cytech.sendium.app.storage;

import gr.cytech.sendium.conf.SendiumConfigurationHandler;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.smpp.client.SmppClientWorker;
import gr.cytech.sendium.routing.AbstractRoutingManager;
import gr.cytech.sendium.routing.OutgoingWorkerManager;
import gr.cytech.sendium.routing.RoutingLookupResult;
import gr.cytech.sendium.routing.StandardRoutingManager;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StandaloneOutboundSchedulingTest {
    @Test
    void productiveBacklogContinuesImmediatelyInBoundedCyclesThenBacksOff() throws Exception {
        try (var fixture = new SchedulingFixture()) {
            fixture.accept(5);
            fixture.runNext();
            verify(fixture.routing, times(2)).lookupForLifecycle(any());
            assertThat(fixture.nextDelay()).isZero();
            fixture.runNext();
            verify(fixture.routing, times(4)).lookupForLifecycle(any());
            assertThat(fixture.nextDelay()).isZero();
            fixture.runNext();
            verify(fixture.routing, times(5)).lookupForLifecycle(any());
            assertThat(fixture.nextDelay()).isZero();
            fixture.runNext();
            verify(fixture.routing, times(5)).lookupForLifecycle(any());
            assertThat(fixture.nextDelay()).isEqualTo(100);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void routingMissesAndFailuresBackOffEvenAfterSelectingWork(boolean fails) throws Exception {
        try (var fixture = new SchedulingFixture()) {
            if (fails) {
                when(fixture.routing.lookupForLifecycle(any())).thenThrow(new IOException("lookup unavailable"));
            } else {
                when(fixture.routing.lookupForLifecycle(any())).thenReturn(new RoutingLookupResult(List.of(), true));
            }
            fixture.accept(1);
            fixture.runNext();
            assertThat(fixture.nextDelay()).isEqualTo(100);
            fixture.runNext();
            assertThat(fixture.nextDelay()).isEqualTo(100);
            verify(fixture.routing, times(4)).lookupForLifecycle(any());
            assertThat(fixture.pipeline.coordinator.selectToRouter(2)).isZero();
        }
    }

    @Test
    void pausedPipelineBacksOffAndResumesItsExistingBacklog() throws Exception {
        try (var fixture = new SchedulingFixture()) {
            fixture.accept(3);
            when(fixture.pipeline.configuration.getBlnPrpt(AbstractRoutingManager._pause)).thenReturn(true);
            fixture.runNext();
            assertThat(fixture.nextDelay()).isEqualTo(100);
            verify(fixture.routing, never()).lookupForLifecycle(any());
            when(fixture.pipeline.configuration.getBlnPrpt(AbstractRoutingManager._pause)).thenReturn(false);
            fixture.runNext();
            assertThat(fixture.nextDelay()).isZero();
            verify(fixture.routing, times(2)).lookupForLifecycle(any());
        }
    }

    @Test
    void pausedDestinationBacksOffAndProviderSchedulingResumesImmediately() throws Exception {
        try (var fixture = new SchedulingFixture()) {
            fixture.accept(1);
            fixture.runNext();
            var provider = new StandaloneOutboundPipelineTest.Providers();
            provider.handoff.complete(null);
            when(fixture.pipeline.workers.getWorkersCopy()).thenReturn(List.of(provider.worker));
            when(provider.worker.isPause()).thenReturn(true);
            fixture.runNext();
            assertThat(fixture.nextDelay()).isEqualTo(100);
            assertThat(provider.calls).hasValue(0);
            when(provider.worker.isPause()).thenReturn(false);
            fixture.runNext();
            assertThat(fixture.nextDelay()).isZero();
            assertThat(provider.submitted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(provider.calls).hasValue(1);
        }
    }

    @Test
    void selectionFailureBacksOffWithoutStoppingTheProcessingChain() throws Exception {
        try (var fixture = new SchedulingFixture()) {
            fixture.accept(1);
            doThrow(new IllegalStateException("selection unavailable")).doCallRealMethod()
                    .when(fixture.pipeline.coordinator).selectToRouter(2);
            fixture.runNext();
            assertThat(fixture.nextDelay()).isEqualTo(100);
            fixture.runNext();
            assertThat(fixture.nextDelay()).isZero();
            verify(fixture.routing, times(1)).lookupForLifecycle(any());
        }
    }

    @Test
    void shutdownFencesQueuedCyclesAndFurtherRescheduling() throws Exception {
        try (var fixture = new SchedulingFixture()) {
            fixture.runNext();
            assertThat(fixture.nextDelay()).isEqualTo(100);
            int scheduled = fixture.cycles.size();
            fixture.close();
            fixture.runNext();
            assertThat(fixture.cycles).hasSize(scheduled);
            verify(fixture.pipeline.coordinator, times(1)).selectToRouter(2);
            verify(fixture.scheduler).shutdown();
        }
    }

    /** Captures production scheduling decisions without real-time latency assertions. */
    private static final class SchedulingFixture implements AutoCloseable {
        private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        private final StandardRoutingManager routing = mock(StandardRoutingManager.class);
        private final StandaloneOutboundPipeline pipeline = new StandaloneOutboundPipeline();
        private final List<Runnable> cycles = new ArrayList<>();
        private final List<Long> delays = new ArrayList<>();
        private int nextCycle;
        private boolean closed;

        private SchedulingFixture() throws Exception {
            var config = mock(Config.class);
            when(config.getOptionalValue(anyString(), eq(Integer.class))).thenReturn(Optional.empty());
            when(config.getOptionalValue("sendium.message.selection-batch-size", Integer.class)).thenReturn(Optional.of(2));
            pipeline.coordinator = spy(StandaloneOutboundPipeline.coordinator(
                    new MessageStorageProfile("memory", "memory", "memory"), config));
            pipeline.config = config;
            pipeline.routing = routing;
            pipeline.workers = mock(OutgoingWorkerManager.class);
            when(pipeline.workers.getWorkersCopy()).thenReturn(List.of());
            when(pipeline.workers.stop()).thenReturn(true);
            pipeline.configuration = mock(SendiumConfigurationHandler.class);
            var provider = mock(SmppClientWorker.class);
            when(provider.getFullName()).thenReturn("smppclient.provider");
            when(routing.lookupForLifecycle(any())).thenReturn(new RoutingLookupResult(List.of(provider), true));
            when(scheduler.awaitTermination(anyLong(), any())).thenReturn(true);
            doAnswer(invocation -> {
                cycles.add(invocation.getArgument(0));
                delays.add(invocation.getArgument(1));
                return null;
            }).when(scheduler).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
            try (var executors = mockStatic(Executors.class, CALLS_REAL_METHODS)) {
                executors.when(() -> Executors.newSingleThreadScheduledExecutor(any(ThreadFactory.class)))
                        .thenReturn(scheduler);
                pipeline.start(null);
            }
            assertThat(nextDelay()).isZero();
        }

        private void accept(int count) {
            for (int i = 0; i < count; i++) {
                var message = new StandardMessage();
                message.body = "message-" + i;
                pipeline.coordinator.accept(new SourceId(UUID.randomUUID()), message);
            }
        }

        private void runNext() {
            cycles.get(nextCycle++).run();
        }

        private long nextDelay() {
            return delays.getLast();
        }

        @Override
        public void close() throws Exception {
            if (!closed) {
                pipeline.stop(null);
                closed = true;
            }
        }
    }
}
