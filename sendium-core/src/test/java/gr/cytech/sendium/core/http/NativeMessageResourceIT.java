package gr.cytech.sendium.core.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import gr.cytech.sendium.auth.CredentialFileWatcher;
import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.worker.DlrService;
import gr.cytech.sendium.core.worker.DlrStorage;
import gr.cytech.sendium.core.worker.MessageState;
import gr.cytech.sendium.core.worker.PostgresqlDlrQuarkusTestResource;
import gr.cytech.sendium.core.worker.StandardMessageTracker;
import gr.cytech.sendium.external.WorkerResourceProvider;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import utils.OutboundIngressFixture;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(NativeMessageResourceIT.Profile.class)
@QuarkusTestResource(value = NativeMessageResourceIT.Resource.class, restrictToAnnotatedClass = true)
class NativeMessageResourceIT {
    @Inject io.micrometer.core.instrument.MeterRegistry metrics;

    @Test
    void bothHttpApisAttributeAcceptedSubmissionsToTheAuthenticatedAccount() throws Exception {
        String account = "metrics-shared-account";
        double before = accountAdmissions(account);
        TimeUnit.MILLISECONDS.sleep(250);
        Files.writeString(Path.of(credentialsPath), "credentials:\n  - type: HTTP\n    apiKey: " + key +
                "\n    accountId: " + account +
                "\n  - type: HTTP\n    systemId: metrics-user\n    password: metrics-password\n    accountId: " + account +
                "\n  - type: HTTP\n    systemId: metrics-second-user\n    password: second-password\n    accountId: " + account + "\n");
        await(() -> credentials.getValidCredentials().containsKey("metrics-user"));
        given().auth().oauth2(key).contentType("application/json")
                .body("{\"from\":\"Brand\",\"to\":\"123\",\"text\":\"Hello\"}")
                .post("/api/v1/messages").then().statusCode(202);
        given().queryParam("username", "metrics-user").queryParam("password", "metrics-password")
                .queryParam("account", "spoofed-owner").queryParam("from", "Brand").queryParam("to", "123")
                .queryParam("text", "Hello").get("/sendsms").then().statusCode(202);
        given().queryParam("username", "metrics-user").queryParam("password", "wrong")
                .queryParam("from", "Brand").queryParam("to", "123").queryParam("text", "Hello")
                .get("/sendsms").then().statusCode(401);
        given().queryParam("username", "metrics-second-user").queryParam("password", "second-password")
                .queryParam("from", "Brand").queryParam("to", "123").queryParam("text", "Hello")
                .get("/sendsms").then().statusCode(202);
        given().auth().oauth2(key).contentType("application/json").body("{}")
                .post("/api/v1/messages").then().statusCode(400);
        assertThat(accountAdmissions(account) - before).isEqualTo(3);
        assertThat(accountAdmissions("spoofed-owner")).isZero();
    }

    private double accountAdmissions(String account) {
        var counter = metrics.find("sendium.sms.account.submissions").tags("accountId", account, "ingress", "http").counter();
        return counter == null ? 0 : counter.count();
    }

    @Inject CredentialFileWatcher credentials;
    @Inject OutboundCoordinator<StandardMessage> coordinator;
    @Inject DlrService dlrs;
    @Inject DlrStorage storage;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name = "smsg.credentials.file.path") String credentialsPath;
    private String key;

    @BeforeEach
    void loadCredential() throws Exception {
        key = UUID.randomUUID().toString();
        replaceCredential(key, "account-one");
    }

    @AfterEach
    void discardRemainingAdmissions() throws Exception {
        coordinator.selectToRouter(10);
        for (var selected = coordinator.takeForRouting(Duration.ZERO); selected.isPresent();
             selected = coordinator.takeForRouting(Duration.ZERO)) {
            coordinator.discard(selected.orElseThrow().id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
        }
    }

    @Test
    void actualCredentialFileReloadAndRemovalAffectSubsequentHttpRequests() throws Exception {
        String original = key;
        given().auth().oauth2(original).contentType("application/json")
                .body("{\"from\":\"Brand\",\"to\":\"123\",\"text\":\"Hello\"}")
                .post("/api/v1/messages").then().statusCode(202);
        key = UUID.randomUUID().toString();
        replaceCredential(key, "account-two");
        given().auth().oauth2(original).contentType("application/json").body("{}")
                .post("/api/v1/messages").then().statusCode(401);
        given().auth().oauth2(key).contentType("application/json")
                .body("{\"from\":\"Brand\",\"to\":\"123\",\"text\":\"Hello\"}")
                .post("/api/v1/messages").then().statusCode(202);
        coordinator.selectToRouter(10);
        var first = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
        var second = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
        assertThat(first.message().owner_id).isEqualTo("account-one");
        assertThat(second.message().owner_id).isEqualTo("account-two");
        coordinator.discard(first.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
        coordinator.discard(second.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();

        TimeUnit.MILLISECONDS.sleep(250); // Space file updates beyond the watcher's existing debounce interval.
        Files.writeString(Path.of(credentialsPath), "credentials: []\n");
        await(() -> credentials.getValidCredentials().isEmpty());
        given().auth().oauth2(key).contentType("application/json").body("{}")
                .post("/api/v1/messages").then().statusCode(401);
    }

    @Test
    @SuppressWarnings("unchecked")
    void admissionThroughTrackingAndPostgresqlReturnsJsonReceiptForTheSameMessage() throws Exception {
        var callbackBody = new CompletableFuture<String>();
        HttpServer callback = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        callback.createContext("/receipt", exchange -> {
            if ("POST".equals(exchange.getRequestMethod()) &&
                    "application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                callbackBody.complete(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        callback.start();
        try {
            String url = "http://" + callback.getAddress().getHostString() + ':' + callback.getAddress().getPort() + "/receipt";
            String id = given().auth().oauth2(key).contentType("application/json")
                    .body(mapper.writeValueAsString(Map.of("from", "Brand", "to", "123", "text", "Hello", "callbackUrl", url)))
                    .post("/api/v1/messages").then().statusCode(202).extract().path("messageId");
            coordinator.selectToRouter(1);
            var selected = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            coordinator.recordToRouted(selected, new Destination<>("native-provider", selected.message()));
            var work = coordinator.takeFromRouted("native-provider", Duration.ZERO).orElseThrow();
            var worker = (AbstractOutWorker<StandardMessage>) mock(AbstractOutWorker.class);
            var resources = mock(WorkerResourceProvider.class);
            when(worker.getWorkerResources()).thenReturn(resources);
            when(worker.getDlrProviderName()).thenReturn("native-provider");
            when(resources.isDlrPersistenceEnabled()).thenReturn(true);
            when(resources.getDlrService()).thenReturn(dlrs);
            var tracker = new StandardMessageTracker(worker);
            coordinator.complete(work.id(), tracker.handoffProviderAccepted("hash", work.message(), "provider-id"))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertThat(storage.getState(id)).get()
                    .extracting(MessageState::getDeliveryStatus).isEqualTo(MessageState.DeliveryStatus.WAITING_PROVIDER);

            dlrs.resolveDlrAsync("native-provider", "provider-id", StandardMessage.DLR_STAT_DELIVRD, "0")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            var receipt = mapper.readTree(callbackBody.get(10, TimeUnit.SECONDS));
            assertThat(receipt.get("messageId").asText()).isEqualTo(id);
            assertThat(receipt.get("status").asText()).isEqualTo("delivered");
            assertThat(receipt.has("errorCode")).isTrue();
            await(() -> storage.getState(id).isEmpty());
        } finally {
            callback.stop(0);
        }
    }

    private void replaceCredential(String apiKey, String account) throws Exception {
        TimeUnit.MILLISECONDS.sleep(250);
        Files.writeString(Path.of(credentialsPath), "credentials:\n  - type: HTTP\n    apiKey: " + apiKey +
                "\n    accountId: " + account + "\n");
        await(() -> credentials.getValidCredentials().containsKey(apiKey));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("sendium.test.native-api", "true");
        }
    }

    @Singleton
    @IfBuildProperty(name = "sendium.test.native-api", stringValue = "true")
    public static class Beans {
        @Produces
        @Singleton
        OutboundCoordinator<StandardMessage> coordinator() {
            return new OutboundIngressFixture(10).coordinator;
        }

        void close(@Disposes OutboundCoordinator<StandardMessage> coordinator) {
            coordinator.close();
        }
    }

    public static class Resource extends PostgresqlDlrQuarkusTestResource {
        private Path file;

        @Override
        public Map<String, String> start() {
            var config = new HashMap<>(super.start());
            try {
                file = Files.createTempFile("sendium-native-credentials-", ".yml");
                Files.writeString(file, "credentials: []\n");
                config.put("smsg.credentials.file.path", file.toString());
                return config;
            } catch (IOException failure) {
                super.stop();
                throw new IllegalStateException("Unable to create native API test credentials", failure);
            }
        }

        @Override
        public void stop() {
            super.stop();
            try {
                if (file != null) {
                    Files.deleteIfExists(file);
                }
            } catch (IOException failure) {
                throw new IllegalStateException("Unable to remove native API test credentials", failure);
            }
        }
    }
}
