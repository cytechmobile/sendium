package gr.cytech.sendium.routing;

import gr.cytech.sendium.conf.SendiumConfigurationHandler;
import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundCoordinator;
import gr.cytech.sendium.core.queue.InMemoryQueueProvider;
import gr.cytech.sendium.core.queue.Queue;
import gr.cytech.sendium.core.smpp.server.SmppServerWorker;
import gr.cytech.sendium.external.WorkerResourceProvider;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.annotation.Annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StandardOutgoingWorkerAdmissionTest {
    @ParameterizedTest
    @ValueSource(strings = {"legacy", "coordinated", "unavailable"})
    @SuppressWarnings("unchecked")
    void ingressBindingIsResolvedBeforeStartupWithoutFailureFallback(String binding) {
        boolean coordinated = !binding.equals("legacy");
        var handler = new StandardOutgoingWorkerHandler();
        handler.availableWorkers = mock(Instance.class);
        Instance<AbstractOutWorker<StandardMessage>> selected = mock(Instance.class);
        SmppServerWorker<StandardMessage> worker = mock(SmppServerWorker.class);
        when(handler.availableWorkers.select(any(Annotation[].class))).thenReturn(selected);
        when(selected.get()).thenReturn(worker);
        when(worker.getType()).thenReturn(SmppServerWorker.TYPE_SMPP_SERVER);
        when(worker.isKeepOnRunning()).thenReturn(true);
        handler.configurationHandler = mock(SendiumConfigurationHandler.class);
        handler.queueProvider = mock(InMemoryQueueProvider.class);
        when(handler.queueProvider.getRouterQueue()).thenReturn(new Queue<>());
        handler.workerResourceProvider = mock(WorkerResourceProvider.class);
        handler.routingManager = mock(StandardRoutingManager.class);
        handler.outboundCoordinators = mock(Instance.class);
        OutboundCoordinator<StandardMessage> coordinator = mock(OutboundCoordinator.class);
        when(handler.outboundCoordinators.isUnsatisfied()).thenReturn(!coordinated);
        when(handler.outboundCoordinators.get()).thenReturn(coordinator);

        if (binding.equals("unavailable")) {
            when(handler.outboundCoordinators.get()).thenThrow(new IllegalStateException("coordinator unavailable"));
            assertThat(handler.startWorker("server", SmppServerWorker.TYPE_SMPP_SERVER)).isNull();
            verify(worker, never()).start();
            verify(worker, never()).setIngressCoordinator(any());
            return;
        }
        assertThat(handler.startWorker("server", SmppServerWorker.TYPE_SMPP_SERVER)).isSameAs(worker);

        if (coordinated) {
            var order = inOrder(worker);
            order.verify(worker).setIngressCoordinator(coordinator);
            order.verify(worker).start();
        } else {
            verify(worker, never()).setIngressCoordinator(any());
            verify(handler.outboundCoordinators, never()).get();
        }
    }
}
