package gr.cytech.sendium.core.http;

import gr.cytech.sendium.auth.CredentialFileWatcher;
import gr.cytech.sendium.conf.SendiumConfigurationHandler;
import gr.cytech.sendium.core.message.DlrReturnMetadata;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.queue.InMemoryQueueProvider;
import gr.cytech.sendium.core.queue.Queue;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KannelResourceTest {
    private static final String USERNAME = "http-user";
    private static final String PASSWORD = "secret";

    private Queue<StandardMessage> routerQueue;
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
    }

    @Test
    void enqueuesSubmissionWithoutDlrMetadataWhenCallbackIsAbsent() throws InterruptedException {
        ArgumentCaptor<StandardMessage> messageCaptor = ArgumentCaptor.forClass(StandardMessage.class);

        Response response = submit(null);

        verify(routerQueue).enqueue(messageCaptor.capture());
        StandardMessage message = messageCaptor.getValue();
        assertThat(response.getStatus()).isEqualTo(Response.Status.ACCEPTED.getStatusCode());
        assertThat(response.getEntity()).isEqualTo(message.serial);
        assertThat(message.acked).isFalse();
        assertThat(message.dlrReturnMetadata).isNull();
    }

    @Test
    void callbackSubmissionCarriesHttpReturnMetadata() throws InterruptedException {
        ArgumentCaptor<StandardMessage> messageCaptor = ArgumentCaptor.forClass(StandardMessage.class);

        Response response = submit("https://callback.test/dlr");

        assertThat(response.getStatus()).isEqualTo(Response.Status.ACCEPTED.getStatusCode());
        verify(routerQueue).enqueue(messageCaptor.capture());
        StandardMessage message = messageCaptor.getValue();
        assertThat(message.acked).isTrue();
        assertThat(message.dlrReturnMetadata.channel()).isEqualTo(DlrReturnMetadata.DeliveryChannel.HTTP);
        assertThat(message.dlrReturnMetadata.accountId()).isEqualTo(USERNAME);
        assertThat(message.dlrReturnMetadata.sourceAddress()).isEqualTo("Sender");
        assertThat(message.dlrReturnMetadata.destinationAddress()).isEqualTo("306910000000");
        assertThat(message.dlrReturnMetadata.forwardDlrUrl()).isEqualTo("https://callback.test/dlr");
    }

    @Test
    void returnsRetryableFailureWhenQueueAdmissionIsInterruptedAfterPersistence() throws InterruptedException {
        doThrow(new InterruptedException("interrupted"))
                .when(routerQueue).enqueue(any(StandardMessage.class));

        Response response = submit(null);

        assertThat(response.getStatus()).isEqualTo(Response.Status.SERVICE_UNAVAILABLE.getStatusCode());
        assertThat(response.getEntity()).isEqualTo("Temporal failure, try again later.");
    }

    private Response submit(String dlrUrl) {
        return resource.receiveSms(
                USERNAME, PASSWORD, "Sender", "306910000000", "Hello", null, null, null,
                null, null, null, null, dlrUrl, null, null, null, null, null, null, null, null);
    }
}
