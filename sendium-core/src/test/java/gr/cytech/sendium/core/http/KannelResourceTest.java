package gr.cytech.sendium.core.http;

import gr.cytech.sendium.auth.CredentialFileWatcher;
import gr.cytech.sendium.conf.SendiumConfigurationHandler;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.queue.InMemoryQueueProvider;
import gr.cytech.sendium.core.queue.Queue;
import gr.cytech.sendium.core.worker.DlrService;
import gr.cytech.sendium.core.worker.DlrStorageException;
import gr.cytech.sendium.core.worker.MessageState;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KannelResourceTest {
    private static final String USERNAME = "http-user";
    private static final String PASSWORD = "secret";

    private Queue<StandardMessage> routerQueue;
    private DlrService dlrService;
    private Instance<DlrService> dlrServices;
    private KannelResource resource;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        InMemoryQueueProvider queueProvider = mock(InMemoryQueueProvider.class);
        routerQueue = mock(Queue.class);
        when(queueProvider.getRouterQueue()).thenReturn(routerQueue);

        CredentialFileWatcher credentials = mock(CredentialFileWatcher.class);
        CredentialFileWatcher.Credential credential = new CredentialFileWatcher.Credential(
                CredentialFileWatcher.CredentialType.HTTP, null, null, USERNAME, PASSWORD, null, Set.of());
        when(credentials.getValidCredentials()).thenReturn(Map.of(USERNAME, credential));

        resource = new KannelResource();
        resource.queueProvider = queueProvider;
        resource.credentialFileWatcher = credentials;
        resource.configurationHandler = mock(SendiumConfigurationHandler.class);
        dlrService = mock(DlrService.class);
        dlrServices = mock(Instance.class);
        when(dlrServices.get()).thenReturn(dlrService);
        resource.dlrServices = dlrServices;
    }

    @Test
    void persistsStateBeforeQueueAdmissionForEverySubmission() throws InterruptedException {
        ArgumentCaptor<MessageState> stateCaptor = ArgumentCaptor.forClass(MessageState.class);
        ArgumentCaptor<StandardMessage> messageCaptor = ArgumentCaptor.forClass(StandardMessage.class);
        InOrder order = inOrder(dlrService, routerQueue);

        Response response = submit(null);

        order.verify(dlrService).saveInitialState(stateCaptor.capture());
        order.verify(routerQueue).enqueue(messageCaptor.capture());
        MessageState state = stateCaptor.getValue();
        StandardMessage message = messageCaptor.getValue();
        assertThat(response.getStatus()).isEqualTo(Response.Status.ACCEPTED.getStatusCode());
        assertThat(response.getEntity()).isEqualTo(message.serial).isEqualTo(state.getGatewayMsgId());
        assertThat(message.acked).isTrue();
        assertThat(state.getForwardDlrUrl()).isNull();
        assertThat(state.getDeliveryChannel()).isEqualTo(MessageState.DeliveryChannel.NONE);
    }

    @Test
    void callbackSubmissionUsesHttpDeliveryChannel() {
        ArgumentCaptor<MessageState> stateCaptor = ArgumentCaptor.forClass(MessageState.class);

        Response response = submit("https://callback.test/dlr");

        assertThat(response.getStatus()).isEqualTo(Response.Status.ACCEPTED.getStatusCode());
        verify(dlrService).saveInitialState(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getDeliveryChannel()).isEqualTo(MessageState.DeliveryChannel.HTTP);
    }

    @Test
    void rejectsBeforeQueueAdmissionWhenPersistenceFails() throws InterruptedException {
        doThrow(new DlrStorageException("database details"))
                .when(dlrService).saveInitialState(any(MessageState.class));

        Response response = submit("https://callback.test/dlr");

        assertThat(response.getStatus()).isEqualTo(Response.Status.SERVICE_UNAVAILABLE.getStatusCode());
        assertThat(response.getEntity()).isEqualTo("Temporal failure, try again later.");
        verify(routerQueue, never()).enqueue(any(StandardMessage.class));
    }

    @Test
    void acceptsSubmissionWithoutDlrTrackingWhenPersistenceIsDisabled() throws InterruptedException {
        when(dlrServices.isUnsatisfied()).thenReturn(true);

        Response response = submit("https://callback.test/dlr");

        assertThat(response.getStatus()).isEqualTo(Response.Status.ACCEPTED.getStatusCode());
        verify(dlrService, never()).saveInitialState(any(MessageState.class));
        verify(routerQueue).enqueue(any(StandardMessage.class));
    }

    @Test
    void returnsRetryableFailureWhenQueueAdmissionIsInterruptedAfterPersistence() throws InterruptedException {
        doThrow(new InterruptedException("interrupted"))
                .when(routerQueue).enqueue(any(StandardMessage.class));

        Response response = submit(null);

        assertThat(response.getStatus()).isEqualTo(Response.Status.SERVICE_UNAVAILABLE.getStatusCode());
        assertThat(response.getEntity()).isEqualTo("Temporal failure, try again later.");
        verify(dlrService).saveInitialState(any(MessageState.class));
    }

    private Response submit(String dlrUrl) {
        return resource.receiveSms(
                USERNAME, PASSWORD, "Sender", "306910000000", "Hello", null, null, null,
                null, null, null, null, dlrUrl, null, null, null, null, null, null, null, null);
    }
}
