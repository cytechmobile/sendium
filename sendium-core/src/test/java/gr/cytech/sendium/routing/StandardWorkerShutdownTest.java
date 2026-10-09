package gr.cytech.sendium.routing;

import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.conf.PropertyChangeEvent;
import gr.cytech.sendium.conf.SendiumConfigurationHandler;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StandardWorkerShutdownTest {
    @Test
    void runtimeWorkerRemovalRetainsItsReturnToRouterPolicy() {
        var handler = new StandardOutgoingWorkerHandler();
        handler.init();
        handler.configurationHandler = mock(SendiumConfigurationHandler.class);
        handler.routingManager = mock(StandardRoutingManager.class);
        var worker = mock(AbstractOutWorker.class);
        when(worker.getInstanceName()).thenReturn("provider");
        when(worker.getFullName()).thenReturn("smpp.provider");
        handler.getWorkers().put("provider", worker);

        handler.propertyChange(new PropertyChangeEvent("outSms.instance.provider.enable", "false"));

        verify(worker).stop();
        verify(worker).dequeueAllToRouter();
        verify(handler.routingManager).beforeWorkerStop(worker);
    }
    @Test
    void applicationShutdownStopsWorkersWithoutSendingTheirQueuesBackToRouting() {
        var handler = new StandardOutgoingWorkerHandler();
        handler.routingManager = mock(StandardRoutingManager.class);
        var worker = mock(AbstractOutWorker.class);
        when(worker.getInstanceName()).thenReturn("provider");
        when(worker.getFullName()).thenReturn("smpp.provider");
        handler.getWorkers().put("provider", worker);

        handler.stopAllWorkers();

        verify(worker).stop();
        verify(worker, never()).dequeueAllToRouter();
        verify(handler.routingManager).beforeStopAll();
    }
}
