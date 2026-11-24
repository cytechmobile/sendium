package gr.cytech.sendium.app.storage;

import gr.cytech.sendium.app.dlr.DlrStartupSupport;
import gr.cytech.sendium.auth.CredentialFileWatcher;
import gr.cytech.sendium.conf.SendiumConfigurationHandler;
import gr.cytech.sendium.core.http.HttpSubmissionMetricsFilter;
import gr.cytech.sendium.core.http.KannelResource;
import gr.cytech.sendium.core.http.NativeApiAuthenticated;
import gr.cytech.sendium.core.http.NativeApiAuthenticationFilter;
import gr.cytech.sendium.core.http.NativeApiError;
import gr.cytech.sendium.core.http.NativeApiExceptionMapper;
import gr.cytech.sendium.core.http.NativeApiPrincipal;
import gr.cytech.sendium.core.http.NativeMessageRequest;
import gr.cytech.sendium.core.http.NativeMessageResource;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.DefaultOutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundCoordinator;
import gr.cytech.sendium.core.queue.InMemoryQueueProvider;
import gr.cytech.sendium.core.storage.memory.MemoryPendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemoryRoutedWorkStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import gr.cytech.sendium.core.worker.ManagedDlrStorage;
import gr.cytech.sendium.core.worker.MemoryDlrReservations;
import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class NativeMessageDlrModeHttpTest {
    @RegisterExtension
    static final QuarkusExtensionTest APP = application("memory");

    public static QuarkusExtensionTest application(String backend) {
        return DlrStartupSupport.application(archive -> archive.addClasses(NativeMessageDlrModeHttpTest.class,
                        NativeMessageResource.class, NativeMessageResource.Accepted.class, NativeMessageRequest.class,
                        NativeApiAuthenticationFilter.class, NativeApiAuthenticated.class, NativeApiError.class,
                        NativeApiExceptionMapper.class, NativeApiPrincipal.class, KannelResource.class,
                        HttpSubmissionMetricsFilter.class, Beans.class, StandaloneMessageSnapshot.class,
                        StandaloneDlrPolicyWiringTest.class))
                .overrideConfigKey("sendium.dlr.persistence.enabled", "true")
                .overrideConfigKey("sendium.dlr.storage.backend", backend)
                .overrideConfigKey("sendium.dlr.storage.memory.max-tracked-submissions", "1");
    }

    @TestHTTPResource("/api/v1/messages") URI endpoint;
    @Inject OutboundCoordinator<StandardMessage> coordinator;
    @Inject ManagedDlrStorage storage;

    @ParameterizedTest
    @ValueSource(strings = {"full", "recovery"})
    void dlrPolicyErrorsPreserveNativeResponse(String scenario) throws Exception {
        if (scenario.equals("full")) {
            assertThat(send(endpoint, true).statusCode()).isEqualTo(202);
        } else {
            ((MemoryDlrReservations) StandaloneDlrPolicyWiringTest.policy(storage)).beginRecovery(2);
        }
        var rejected = send(endpoint, true);
        assertThat(rejected.statusCode()).isEqualTo(503);
        assertThat(rejected.headers().firstValue("Content-Type").orElseThrow()).contains("application/json");
        assertThat(rejected.body()).contains("\"code\":\"ADMISSION_UNAVAILABLE\"").doesNotContain("callback.test");
        assertThat(send(endpoint, false).statusCode()).isEqualTo(202);
        assertThat(coordinator.selectToRouter(8)).isEqualTo(scenario.equals("full") ? 2 : 1);
    }

    @AfterEach
    void clean() throws Exception {
        coordinator.selectToRouter(8);
        while (true) {
            var selected = coordinator.takeForRouting(Duration.ZERO);
            if (selected.isEmpty()) {
                return;
            }
            coordinator.discard(selected.orElseThrow().id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
        }
    }

    public static HttpResponse<String> send(URI endpoint, boolean callback) throws Exception {
        String body = "{\"from\":\"sender\",\"to\":\"12345\",\"text\":\"synthetic\"" +
                (callback ? ",\"callbackUrl\":\"https://callback.test/dlr\"" : "") + "}";
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer native-key").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Singleton
    public static class Beans {
        @Produces
        @Singleton
        OutboundCoordinator<StandardMessage> coordinator(ManagedDlrStorage storage) {
            var pending = new MemoryPendingMessageStore<StandardMessage>(8, StandaloneMessageSnapshot::copy);
            var result = new DefaultOutboundCoordinator<>(pending, new MemorySelectedRouterStore<>(pending, 8),
                    new MemoryRoutedWorkStore<>(8, StandaloneMessageSnapshot::copy), 8, StandaloneDlrPolicyWiringTest.policy(storage));
            result.start();
            return result;
        }

        void close(@Disposes OutboundCoordinator<StandardMessage> coordinator) {
            coordinator.close();
        }

        @Produces
        @Singleton
        CredentialFileWatcher credentials() {
            var credentials = mock(CredentialFileWatcher.class);
            var credential = new CredentialFileWatcher.Credential(CredentialFileWatcher.CredentialType.HTTP,
                    "account", "product", "http-user", "secret", "native-key", Set.of());
            when(credentials.getValidCredentials()).thenReturn(Map.of("native-key", credential, "http-user", credential));
            return credentials;
        }

        @Produces
        @Singleton
        InMemoryQueueProvider queues() {
            return mock(InMemoryQueueProvider.class);
        }

        @Produces
        @Singleton
        SendiumConfigurationHandler configuration() {
            return mock(SendiumConfigurationHandler.class);
        }
    }
}
