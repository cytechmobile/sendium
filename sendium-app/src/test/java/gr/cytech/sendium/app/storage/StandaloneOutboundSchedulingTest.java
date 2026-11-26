package gr.cytech.sendium.app.storage;

import gr.cytech.sendium.conf.SendiumConfigurationHandler;
import gr.cytech.sendium.core.message.DlrReturnMetadata;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.DefaultOutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundDlrPolicy;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.smpp.client.SmppClientWorker;
import gr.cytech.sendium.core.storage.file.FilePendingMessageStore;
import gr.cytech.sendium.core.storage.file.FileRoutedWorkStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import gr.cytech.sendium.routing.AbstractRoutingManager;
import gr.cytech.sendium.routing.OutgoingWorkerManager;
import gr.cytech.sendium.routing.RoutingLookupResult;
import gr.cytech.sendium.routing.StandardRoutingManager;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

@Timeout(30)
class StandaloneOutboundSchedulingTest {
    @TempDir Path root;

    @Test
    void onlyDeferredWorkUsesDelayedRetry() throws Exception {
        try (var fixture = new SchedulingFixture(root)) {
            when(fixture.provider.acceptsMessages()).thenReturn(true);
            when(fixture.pipeline.workers.getWorkersCopy()).thenReturn(List.of(fixture.provider));
            fixture.accept(5, true);
            for (int cycle = 0; cycle < 3; cycle++) {
                fixture.runNext();
            }
            fixture.runNext();
            assertThat(fixture.nextDelay()).isEqualTo(100);
            fixture.runNext();
            assertThat(fixture.nextDelay()).isEqualTo(100);
            assertThat(fixture.pipeline.call().getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
            var field = FileRoutedWorkStore.class.getDeclaredField("active");
            field.setAccessible(true);
            // The inspected candidate relinquishes a slot instead of remaining pinned
            // in the two-item window. The old release-only implementation retains both.
            assertThat(((Map<?, ?>) field.get(fixture.fileRouted)).size()).isLessThan(2);
            assertThat(fixture.filePending.sourcesAfter(null, 16)).hasSize(5);
        }
    }

    @Test
    void recoverableCycleFailureBacksOffAndKeepsReadinessUp() throws Exception {
        try (var fixture = new SchedulingFixture()) {
            doThrow(new IllegalStateException("cycle unavailable")).doCallRealMethod().when(fixture.pipeline).process();
            fixture.runNext();
            assertThat(fixture.nextDelay()).isEqualTo(100);
            assertThat(fixture.pipeline.call().getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
            fixture.accept(1);
            fixture.runNext();
            assertThat(fixture.nextDelay()).isZero();
            verify(fixture.routing).lookupForLifecycle(any());
        }
    }

    @Test
    void fatalCycleFailureMarksReadinessDownAndDoesNotSilentlyReschedule() throws Exception {
        try (var fixture = new SchedulingFixture()) {
            var failure = new LinkageError("simulated fatal processor failure");
            doThrow(failure).when(fixture.pipeline.coordinator).selectToRouter(2);
            assertThatThrownBy(fixture::runNext).isSameAs(failure);
            assertThat(fixture.cycles).hasSize(1);
            var health = fixture.pipeline.call();
            assertThat(health.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
            assertThat(health.getData().orElseThrow()).containsEntry("failureType", LinkageError.class.getName());
        }
    }

    @Test
    void failedReschedulingMarksReadinessDown() throws Exception {
        try (var fixture = new SchedulingFixture()) {
            doThrow(new java.util.concurrent.RejectedExecutionException("scheduler unavailable"))
                    .when(fixture.scheduler).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
            assertThatThrownBy(fixture::runNext).isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
            assertThat(fixture.pipeline.call().getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        }
    }

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
        private final SmppClientWorker<StandardMessage> provider = mock(SmppClientWorker.class);
        private final StandaloneOutboundPipeline pipeline = spy(new StandaloneOutboundPipeline());
        private final List<Runnable> cycles = new ArrayList<>();
        private final List<Long> delays = new ArrayList<>();
        private int nextCycle;
        private boolean closed;
        private FilePendingMessageStore filePending;
        private FileRoutedWorkStore fileRouted;

        private SchedulingFixture() throws Exception {
            this(null);
        }

        private SchedulingFixture(Path root) throws Exception {
            var config = mock(Config.class);
            when(config.getOptionalValue(anyString(), eq(Integer.class))).thenReturn(Optional.empty());
            when(config.getOptionalValue("sendium.message.selection-batch-size", Integer.class)).thenReturn(Optional.of(2));
            if (root == null) {
                pipeline.coordinator = spy(StandaloneOutboundPipeline.coordinator(
                        new MessageStorageProfile("memory", "memory", "memory"), config,
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
            } else {
                filePending = new FilePendingMessageStore(root, 32);
                fileRouted = new FileRoutedWorkStore(filePending, 32, 2);
                OutboundDlrPolicy disabled = new OutboundDlrPolicy() {
                    @Override
                    public Reservation reserveAdmission(SourceId source, StandardMessage message) {
                        return Reservation.UNCHANGED;
                    }

                    @Override
                    public void prepareProvider(String id, Set<SourceId> required) {
                        if (!required.isEmpty()) {
                            throw new Blocked(BlockReason.DISABLED, required.size(), 0);
                        }
                    }
                };
                pipeline.coordinator = spy(new DefaultOutboundCoordinator<>(filePending,
                        new MemorySelectedRouterStore<>(filePending, 32), fileRouted, 2, disabled));
                pipeline.coordinator.start();
            }
            pipeline.config = config;
            pipeline.routing = routing;
            pipeline.workers = mock(OutgoingWorkerManager.class);
            when(pipeline.workers.getWorkersCopy()).thenReturn(List.of());
            when(pipeline.workers.stop()).thenReturn(true);
            pipeline.configuration = mock(SendiumConfigurationHandler.class);
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
            accept(count, false);
        }

        private void accept(int count, boolean dlr) {
            for (int i = 0; i < count; i++) {
                var message = new StandardMessage();
                message.body = "message-" + i;
                var source = new SourceId(UUID.randomUUID());
                message.serial = source.value().toString();
                if (dlr) {
                    message.dlrReturnMetadata = DlrReturnMetadata.http("account", "sender", "recipient", "https://callback.test");
                }
                pipeline.coordinator.accept(source, message);
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
