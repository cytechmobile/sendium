package gr.cytech.sendium.app.storage;

import gr.cytech.sendium.core.http.KannelResource;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundCoordinator;
import gr.cytech.sendium.core.queue.InMemoryQueueProvider;
import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class FilePendingHttpTest {
    @RegisterExtension
    static final QuarkusExtensionTest APP = new QuarkusExtensionTest()
            .withApplicationRoot(archive -> archive.addClasses(StandaloneOutboundPipeline.class,
                    StandaloneOutboundPipeline.LifecycleRouting.class, StandaloneMessageSnapshot.class,
                    StandaloneMessageStorage.class, MessageStorageProfile.class, KannelResource.class,
                    StandaloneOutboundPipelineTest.Beans.class, StandaloneOutboundPipelineTest.Providers.class,
                    MessageLifecycleHttpTest.Credentials.class))
            .overrideConfigKey("quarkus.arc.exclude-dependency.core.group-id", "gr.cytech")
            .overrideConfigKey("quarkus.arc.exclude-dependency.core.artifact-id", "sendium-core")
            .overrideConfigKey("sendium.dlr.persistence.enabled", "false")
            .overrideConfigKey(MessageStorageProfile.PENDING_BACKEND, "file")
            .overrideConfigKey(MessageStorageProfile.PENDING_PATH, spool())
            .overrideConfigKey("sendium.message.pending.capacity", "1")
            .overrideConfigKey("quarkus.http.test-port", "0");

    @TestHTTPResource("/sendsms") URI endpoint;
    @Inject Config config;
    @Inject OutboundCoordinator<StandardMessage> coordinator;
    @Inject StandaloneOutboundPipelineTest.Providers providers;
    @Inject InMemoryQueueProvider legacy;

    @Test
    void configuredFileAdmissionCommitsBeforeHttpSuccessAndRetainsSourcesThroughHandoff() throws Exception {
        assertThat(coordinator.pendingMultipartRecovery().orElseThrow().isReady()).isTrue();
        try (HttpClient client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(endpoint +
                    "?username=http-user&password=secret&from=Sender&to=306900000001&text=Hello"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(202);
            var source = Path.of(config.getValue(MessageStorageProfile.PENDING_PATH, String.class))
                    .resolve("sources").resolve(response.body().substring(0, 2)).resolve(response.body() + ".cbor");
            assertThat(source).isRegularFile();
            assertThat(Files.size(source)).isPositive();
            assertThat(providers.submitted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(providers.message.serial).isEqualTo(response.body());
            assertThat(source).exists();
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(503);
            assertThat(legacy.getRouterQueue().isEmpty()).isTrue();
            providers.handoff.complete(null);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (Files.exists(source) && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
            assertThat(source).doesNotExist();
        } finally {
            providers.handoff.complete(null);
        }
    }

    private static String spool() {
        try {
            return Files.createTempDirectory("sendium-file-http-").toString();
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
