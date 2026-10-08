package gr.cytech.sendium.app.storage;

import gr.cytech.sendium.auth.CredentialFileWatcher;
import gr.cytech.sendium.conf.SendiumConfigurationHandler;
import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.core.http.KannelResource;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundCoordinator;
import gr.cytech.sendium.core.queue.InMemoryQueueProvider;
import gr.cytech.sendium.core.smpp.client.CoordinatedSmppSubmission;
import gr.cytech.sendium.core.smpp.client.SmppClientWorker;
import gr.cytech.sendium.routing.OutgoingWorkerManager;
import gr.cytech.sendium.routing.RoutingLookupResult;
import gr.cytech.sendium.routing.StandardRoutingManager;
import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StandaloneOutboundPipelineTest {
    @RegisterExtension
    static final QuarkusExtensionTest APP = new QuarkusExtensionTest()
            .withApplicationRoot(archive -> archive.addClasses(StandaloneOutboundPipeline.class,
                    StandaloneOutboundPipeline.LifecycleRouting.class,
                    StandaloneMessageSnapshot.class, StandaloneMessageStorage.class, MessageStorageProfile.class,
                    KannelResource.class, Beans.class, Providers.class, MessageLifecycleHttpTest.Credentials.class))
            .overrideConfigKey("quarkus.arc.exclude-dependency.core.group-id", "gr.cytech")
            .overrideConfigKey("quarkus.arc.exclude-dependency.core.artifact-id", "sendium-core")
            .overrideConfigKey("sendium.dlr.persistence.enabled", "false")
            .overrideConfigKey("sendium.message.pending.capacity", "1")
            .overrideConfigKey("quarkus.http.test-port", "0");

    @TestHTTPResource("/sendsms") URI endpoint;
    @Inject Providers providers;
    @Inject InMemoryQueueProvider legacy;
    @Inject OutboundCoordinator<StandardMessage> coordinator;

    @Test
    void productionAssemblyProcessesHttpThroughProviderCompletionWithoutLegacyAdmission() throws Exception {
        URI uri = URI.create(endpoint + "?username=http-user&password=secret&from=Sender&to=306900000001&text=Hello");
        try (HttpClient client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(202);
            assertThat(providers.submitted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(providers.message.serial).isEqualTo(response.body());
            assertThat(providers.message.body).isEqualTo("Hello");
            assertThat(legacy.getRouterQueue().isEmpty()).isTrue();
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(503);
            assertThat(coordinator.selectToRouter(1)).isZero();

            providers.handoff.complete(null);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            int status;
            do {
                status = client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
                if (status == 503) {
                    TimeUnit.MILLISECONDS.sleep(20);
                }
            } while (status == 503 && System.nanoTime() < deadline);
            assertThat(status).isEqualTo(202);
            while (providers.calls.get() < 2 && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
            assertThat(providers.calls.get()).isEqualTo(2);
            assertThat(legacy.getRouterQueue().isEmpty()).isTrue();
        } finally {
            providers.handoff.complete(null);
        }
    }

    @Singleton
    public static class Providers implements OutgoingWorkerManager {
        final CompletableFuture<Void> handoff = new CompletableFuture<>();
        final CountDownLatch submitted = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        volatile StandardMessage message;
        final SmppClientWorker<StandardMessage> worker;

        @SuppressWarnings("unchecked")
        public Providers() throws Exception {
            worker = mock(SmppClientWorker.class);
            when(worker.getFullName()).thenReturn("smppclient.provider");
            when(worker.isKeepOnRunning()).thenReturn(true);
            when(worker.acceptsMessages()).thenReturn(true);
            when(worker.getThreadCount()).thenReturn(1);
            when(worker.submitPreparedCoordinated(any(), any())).thenAnswer(invocation -> {
                message = invocation.getArgument(0);
                calls.incrementAndGet();
                var submission = mock(CoordinatedSmppSubmission.class);
                when(submission.completion()).thenReturn(handoff);
                submitted.countDown();
                return submission;
            });
        }

        @Override
        public boolean stop() {
            return true;
        }

        @Override
        public Collection<AbstractOutWorker> getWorkersCopy() {
            return List.of(worker);
        }
    }

    @Singleton
    public static class Beans {
        @Produces @Singleton
        StandardRoutingManager routing(Providers providers) throws Exception {
            var routing = mock(StandardRoutingManager.class);
            when(routing.lookupForLifecycle(any())).thenReturn(new RoutingLookupResult(List.of(providers.worker), true));
            return routing;
        }

        @Produces @Singleton
        CredentialFileWatcher credentials() {
            return new MessageLifecycleHttpTest.Credentials();
        }

        @Produces @Singleton
        SendiumConfigurationHandler configuration() {
            var configuration = new SendiumConfigurationHandler();
            configuration.init();
            return configuration;
        }

        @Produces @Singleton
        InMemoryQueueProvider legacyQueues() {
            return new InMemoryQueueProvider();
        }
    }
}
