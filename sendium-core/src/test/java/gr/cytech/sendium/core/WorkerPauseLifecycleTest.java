package gr.cytech.sendium.core;

import gr.cytech.sendium.conf.SendiumConfigurationProvider;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.queue.Queue;
import gr.cytech.sendium.core.queue.QueueProvider;
import gr.cytech.sendium.core.worker.Tracker;
import gr.cytech.sendium.external.WorkerResourceProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkerPauseLifecycleTest {
    @Test
    void pauseBeforeInitializationDoesNotSubscribe() {
        var resources = mock(WorkerResourceProvider.class);
        var worker = new TestWorker(resources);
        worker.setPause(true, false, false);
        assertThat(worker.isPause()).isTrue();
        verifyNoInteractions(resources);
    }

    @Test
    void unpauseBeforeInitializationDoesNotSubscribe() {
        var resources = mock(WorkerResourceProvider.class);
        var worker = new TestWorker(resources);
        worker.pause = true;
        worker.setPause(false, false, false);
        assertThat(worker.isPause()).isFalse();
        verifyNoInteractions(resources);
    }

    @Test
    void initializedPauseAndResumePreserveQueueSubscriptions() {
        var resources = mock(WorkerResourceProvider.class);
        var provider = mock(QueueProvider.class);
        when(resources.geQueueProvider()).thenReturn(provider);
        when(provider.subscribe(anyString(), anyString(), anyBoolean())).thenReturn(new Queue<StandardMessage>());
        var worker = new TestWorker(resources);
        worker.init(resources, mock(Tracker.class));
        worker.setPause(true, false, false);
        assertThat(worker.isPause()).isTrue();
        worker.setPause(false, false, false);
        assertThat(worker.isPause()).isFalse();
        verify(provider, times(3)).subscribe(worker.getFullName(), worker.getQueueName(), false);
        worker.setPause(false, true, false);
        verify(provider).unsubscribe(worker.getFullName(), worker.getQueueName());
        worker.setPause(false, false, false);
        verify(provider, times(4)).subscribe(worker.getFullName(), worker.getQueueName(), false);
    }

    private static final class TestWorker extends AbstractOutWorker<StandardMessage> {
        private TestWorker(WorkerResourceProvider resources) {
            super(mock(SendiumConfigurationProvider.class), new Queue<>(), "pause-test", "worker");
            workerResources = resources;
        }

        @Override
        public StandardMessage doMessage(int threadIndex, StandardMessage message) {
            return message;
        }

        @Override
        public String getType() {
            return "pause-test";
        }
    }
}
