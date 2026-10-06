package gr.cytech.sendium.app.storage;

import gr.cytech.sendium.conf.SendiumConfigurationHandler;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.routing.OutgoingWorkerManager;
import gr.cytech.sendium.routing.RoutingLookupResult;
import gr.cytech.sendium.routing.StandardRoutingManager;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StandaloneOutboundShutdownTest {
    @Test
    void standaloneShutdownDrainsProvidersAndRefusesStageClosureUntilWorkersStop() throws Exception {
        var config = mock(Config.class);
        when(config.getOptionalValue(anyString(), eq(Integer.class))).thenReturn(Optional.empty());
        var coordinator = spy(StandaloneOutboundPipeline.coordinator(new MessageStorageProfile("memory", "memory", "memory"), config));
        var quiesced = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            quiesced.countDown();
            return null;
        }).when(coordinator).quiesce();
        var provider = new StandaloneOutboundPipelineTest.Providers();
        var routing = mock(StandardRoutingManager.class);
        when(routing.lookupForLifecycle(any())).thenReturn(new RoutingLookupResult(List.of(provider.worker), true));
        var workers = mock(OutgoingWorkerManager.class);
        when(workers.getWorkersCopy()).thenReturn(List.of(provider.worker));
        when(workers.stop()).thenReturn(false, true);
        var pipeline = new StandaloneOutboundPipeline();
        pipeline.coordinator = coordinator;
        pipeline.routing = routing;
        pipeline.workers = workers;
        pipeline.config = config;
        pipeline.configuration = mock(SendiumConfigurationHandler.class);
        pipeline.start(null);

        var message = new StandardMessage();
        message.body = "hello";
        coordinator.admit(new SourceId(UUID.randomUUID()), message);
        try (var stopping = Executors.newSingleThreadExecutor()) {
            try {
                assertThat(provider.submitted.await(5, TimeUnit.SECONDS)).isTrue();
                var stopped = stopping.submit(() -> {
                    pipeline.stop(null);
                    return null;
                });
                assertThat(quiesced.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(stopped.isDone()).isFalse();
                verify(workers, never()).stop();
                verify(coordinator, never()).close();
                assertThatThrownBy(() -> coordinator.admit(new SourceId(UUID.randomUUID()), message))
                        .isInstanceOf(OutboundStorageException.class);
                provider.handoff.complete(null);
                assertThatThrownBy(() -> stopped.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
                verify(coordinator, never()).close();
                pipeline.stop(null);
                var order = inOrder(coordinator, routing, workers);
                order.verify(coordinator).quiesce();
                order.verify(routing).stop();
                order.verify(workers).stop();
                order.verify(coordinator).close();
            } finally {
                provider.handoff.complete(null);
            }
        } finally {
            pipeline.stop(null);
        }
    }
}
