package gr.cytech.sendium.app.storage;

import gr.cytech.sendium.auth.CredentialFileWatcher;
import gr.cytech.sendium.conf.SendiumConfigurationHandler;
import gr.cytech.sendium.core.http.KannelResource;
import gr.cytech.sendium.core.message.DlrReturnMetadata;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.DefaultOutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundCoordinator;
import gr.cytech.sendium.core.queue.InMemoryQueueProvider;
import gr.cytech.sendium.core.storage.memory.MemoryPendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemoryRoutedWorkStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Vetoed;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class SmsLifecycleHttpTest {
    @RegisterExtension
    static final QuarkusExtensionTest APP = new QuarkusExtensionTest()
            .withApplicationRoot(archive -> archive.addClasses(KannelResource.class, Beans.class, Credentials.class,
                    StandaloneSmsStorage.class, SmsStorageProfile.class))
            .overrideConfigKey("quarkus.arc.exclude-dependency.core.group-id", "gr.cytech")
            .overrideConfigKey("quarkus.arc.exclude-dependency.core.artifact-id", "sendium-core")
            .overrideConfigKey("sendium.dlr.persistence.enabled", "false")
            .overrideConfigKey("quarkus.http.test-port", "0");

    @TestHTTPResource("/sendsms")
    URI endpoint;

    @Inject
    OutboundCoordinator<StandardMessage> coordinator;

    @Inject
    InMemoryQueueProvider legacy;

    @Test
    void httpUsesTheExplicitCoordinatorBeanAndReturnsBackpressureAtCapacity() throws Exception {
        URI requestUri = URI.create(endpoint + "?username=http-user&password=secret&from=Sender&to=306900000001&text=Hello" +
                "&dlr-url=https%3A%2F%2Fcallback.test%2Fdlr");
        try (HttpClient client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(requestUri).timeout(Duration.ofSeconds(5)).GET().build();
            var accepted = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(accepted.statusCode()).isEqualTo(202);
            var full = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(full.statusCode()).isEqualTo(503);
            assertThat(legacy.getRouterQueue().isEmpty()).isTrue();
            assertThat(coordinator.selectAndStage(1)).isEqualTo(1);
            var selected = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(selected.message().serial).isEqualTo(accepted.body());
            assertThat(selected.sources()).singleElement().satisfies(source ->
                    assertThat(source.value()).isEqualTo(UUID.fromString(accepted.body())));
            assertThat(selected.message().body).isEqualTo("Hello");
            assertThat(selected.message().dlrReturnMetadata).isEqualTo(
                    DlrReturnMetadata.http("http-user", "Sender", "306900000001", "https://callback.test/dlr"));
            coordinator.discard(selected.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
        }
    }

    @Singleton
    public static class Beans {
        @Produces
        @Singleton
        OutboundCoordinator<StandardMessage> coordinator() {
            var pending = new MemoryPendingMessageStore<StandardMessage>(1, Beans::snapshot);
            var router = new MemorySelectedRouterStore<>(pending, 1);
            var routed = new MemoryRoutedWorkStore<StandardMessage>(1, Beans::snapshot);
            var coordinator = new DefaultOutboundCoordinator<>(pending, router, routed);
            coordinator.start();
            return coordinator;
        }

        void stop(@Disposes OutboundCoordinator<StandardMessage> coordinator) {
            coordinator.close();
        }

        @Produces
        @Singleton
        CredentialFileWatcher credentials() {
            return new Credentials();
        }

        @Produces
        @Singleton
        SendiumConfigurationHandler configuration() {
            var configuration = new SendiumConfigurationHandler();
            configuration.init();
            return configuration;
        }

        @Produces
        @Singleton
        InMemoryQueueProvider legacyQueues() {
            return new InMemoryQueueProvider();
        }

        private static StandardMessage snapshot(StandardMessage message) {
            // This HTTP fixture contains only scalar fields and immutable return metadata.
            try {
                return (StandardMessage) message.clone();
            } catch (CloneNotSupportedException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    @Vetoed
    public static class Credentials extends CredentialFileWatcher {
        @Override
        public Map<String, Credential> getValidCredentials() {
            return Map.of("http-user", new Credential(CredentialType.HTTP, null, null,
                    "http-user", "secret", null, Set.of()));
        }
    }
}
