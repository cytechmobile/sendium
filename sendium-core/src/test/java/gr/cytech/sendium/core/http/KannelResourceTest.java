package gr.cytech.sendium.core.http;

import gr.cytech.sendium.auth.CredentialFileWatcher;
import gr.cytech.sendium.conf.SendiumConfigurationHandler;
import gr.cytech.sendium.core.message.DlrReturnMetadata;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.queue.InMemoryQueueProvider;
import gr.cytech.sendium.core.queue.Queue;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import utils.OutboundIngressFixture;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        resource.outboundCoordinators = mock(Instance.class);
        when(resource.outboundCoordinators.isUnsatisfied()).thenReturn(true);
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
    void returnsRetryableFailureWhenQueueAdmissionIsInterrupted() throws InterruptedException {
        doThrow(new InterruptedException("interrupted"))
                .when(routerQueue).enqueue(any(StandardMessage.class));

        try {
            Response response = submit(null);

            assertThat(response.getStatus()).isEqualTo(Response.Status.SERVICE_UNAVAILABLE.getStatusCode());
            assertThat(response.getEntity()).isEqualTo("Temporal failure, try again later.");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private Response submit(String dlrUrl) {
        return resource.receiveSms(
                USERNAME, PASSWORD, "Sender", "306910000000", "Hello", null, null, null,
                null, null, null, null, dlrUrl, null, null, null, null, null, null, null, null);
    }

    @Test
    void coordinatorAdmissionOwnsTheMessageBeforeHttpSuccess() {
        try (var lifecycle = new OutboundIngressFixture(1)) {
            when(resource.outboundCoordinators.isUnsatisfied()).thenReturn(false);
            when(resource.outboundCoordinators.get()).thenReturn(lifecycle.coordinator);
            Response response = submit("https://callback.test/dlr");
            var source = ArgumentCaptor.forClass(SourceId.class);
            var message = ArgumentCaptor.forClass(StandardMessage.class);
            verify(lifecycle.coordinator).admit(source.capture(), message.capture());
            assertThat(response.getStatus()).isEqualTo(202);
            assertThat(source.getValue().value()).isEqualTo(UUID.fromString((String) response.getEntity()));
            var retained = lifecycle.pending.find(source.getValue()).orElseThrow();
            assertThat(retained.serial).isEqualTo(response.getEntity());
            assertThat(retained.body).isEqualTo("Hello");
            assertThat(retained.dlrReturnMetadata).isEqualTo(message.getValue().dlrReturnMetadata);
            assertThat(retained.dlrReturnMetadata.channel()).isEqualTo(DlrReturnMetadata.DeliveryChannel.HTTP);
            verifyNoInteractions(routerQueue);
        }
    }

    @Test
    void fullOrQuiescingCoordinatorRejectsWithoutFallingBackToTheQueue() {
        try (var lifecycle = new OutboundIngressFixture(1)) {
            when(resource.outboundCoordinators.isUnsatisfied()).thenReturn(false);
            when(resource.outboundCoordinators.get()).thenReturn(lifecycle.coordinator);
            assertThat(submit(null).getStatus()).isEqualTo(202);
            assertThat(submit(null).getStatus()).isEqualTo(503);
            lifecycle.coordinator.quiesce();
            assertThat(submit(null).getStatus()).isEqualTo(503);
            verifyNoInteractions(routerQueue);
        }
    }

    @ParameterizedTest
    @EnumSource(OutboundStorageException.Reason.class)
    @SuppressWarnings("unchecked")
    void mapsStorageFailuresWithoutLeakingTheirDetails(OutboundStorageException.Reason reason) {
        OutboundCoordinator<StandardMessage> coordinator = mock(OutboundCoordinator.class);
        when(resource.outboundCoordinators.isUnsatisfied()).thenReturn(false);
        when(resource.outboundCoordinators.get()).thenReturn(coordinator);
        doThrow(new OutboundStorageException(OutboundStage.Role.PENDING, reason, "private storage details"))
                .when(coordinator).admit(any(), any());
        Response response = submit(null);
        int expected = switch (reason) {
            case UNSUPPORTED -> 400;
            case INVALID_TRANSITION -> 500;
            default -> 503;
        };
        assertThat(response.getStatus()).isEqualTo(expected);
        assertThat(response.getEntity().toString()).doesNotContain("private storage details");
        verifyNoInteractions(routerQueue);
    }

    @Test
    void failingCoordinatorResolutionDoesNotUseLegacyAdmission() {
        when(resource.outboundCoordinators.isUnsatisfied()).thenReturn(false);
        when(resource.outboundCoordinators.get()).thenThrow(new IllegalStateException("coordinator unavailable"));
        assertThat(submit(null).getStatus()).isEqualTo(500);
        verifyNoInteractions(routerQueue);
    }
}
