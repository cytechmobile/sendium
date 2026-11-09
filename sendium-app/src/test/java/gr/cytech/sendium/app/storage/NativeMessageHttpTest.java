package gr.cytech.sendium.app.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.cytech.sendium.auth.CredentialFileWatcher;
import gr.cytech.sendium.core.http.NativeApiAuthenticated;
import gr.cytech.sendium.core.http.HttpSubmissionMetricsFilter;
import gr.cytech.sendium.core.http.NativeApiAuthenticationFilter;
import gr.cytech.sendium.core.http.NativeApiError;
import gr.cytech.sendium.core.http.NativeApiExceptionMapper;
import gr.cytech.sendium.core.http.NativeApiPrincipal;
import gr.cytech.sendium.core.http.NativeMessageRequest;
import gr.cytech.sendium.core.http.NativeMessageResource;
import gr.cytech.sendium.core.message.DlrReturnMetadata.HttpCallbackFormat;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.DefaultOutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundCoordinator;
import gr.cytech.sendium.core.storage.memory.MemoryPendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemoryRoutedWorkStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import io.quarkus.test.QuarkusExtensionTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NativeMessageHttpTest {
    private static final String VALID = "{\"from\":\"Sendium\",\"to\":\"+306900000000\",\"text\":\"Hello + 10%\"}";
    @RegisterExtension
    static final QuarkusExtensionTest APP = application();

    static QuarkusExtensionTest application() {
        return new QuarkusExtensionTest()
            .withApplicationRoot(archive -> archive.addClasses(NativeMessageHttpTest.class, NativeMessageResource.class,
                    NativeMessageResource.Accepted.class, NativeMessageRequest.class, NativeApiAuthenticationFilter.class,
                    NativeApiAuthenticated.class, NativeApiError.class, NativeApiExceptionMapper.class, NativeApiPrincipal.class,
                    StandaloneMessageSnapshot.class, Beans.class, HttpSubmissionMetricsFilter.class))
            .overrideConfigKey("quarkus.arc.exclude-dependency.core.group-id", "gr.cytech")
            .overrideConfigKey("quarkus.arc.exclude-dependency.core.artifact-id", "sendium-core")
            .overrideConfigKey("sendium.dlr.persistence.enabled", "false")
            .overrideConfigKey("quarkus.http.test-port", "0");
    }

    @TestHTTPResource("/api/v1/messages") URI endpoint;
    @Inject OutboundCoordinator<StandardMessage> coordinator;
    @Inject CredentialFileWatcher credentials;
    @Inject ObjectMapper mapper;
    @Inject MeterRegistry metrics;

    @BeforeEach
    void credentials() {
        useCredential("account-one", Set.of());
    }

    @AfterEach
    void cleanPending() throws Exception {
        coordinator.selectToRouter(1);
        var selected = coordinator.takeForRouting(Duration.ZERO);
        if (selected.isPresent()) {
            coordinator.discard(selected.orElseThrow().id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
        }
    }

    @Test
    void countsAdmissionAndPreResourceRejectionsExactlyOnce() throws Exception {
        double acceptedBefore = submissions("accepted");
        double rejectedBefore = submissions("rejected");
        assertThat(send(endpoint, VALID, null).statusCode()).isEqualTo(401);
        assertThat(submit("{").statusCode()).isEqualTo(400);
        assertThat(submit(VALID).statusCode()).isEqualTo(202);
        assertThat(submit(VALID).statusCode()).isEqualTo(503);
        try (HttpClient client = HttpClient.newHttpClient()) {
            var scrape = client.send(HttpRequest.newBuilder(endpoint.resolve("/q/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(scrape.statusCode()).isEqualTo(200);
            assertThat(scrape.body()).contains("sendium_sms_submissions_total");
        }
        assertThat(submissions("accepted")).isEqualTo(acceptedBefore + 1);
        assertThat(submissions("rejected")).isEqualTo(rejectedBefore + 3);
    }

    private double submissions(String result) {
        var counter = metrics.find("sendium.sms.submissions").tag("ingress", "http").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void admitsWithAuthenticatedOwnershipDefaultsAndAnActualSourceIdentifier() throws Exception {
        var response = submit(VALID);
        assertThat(response.statusCode()).isEqualTo(202);
        JsonNode accepted = mapper.readTree(response.body());
        assertThat(accepted.get("status").asText()).isEqualTo("accepted");
        UUID id = UUID.fromString(accepted.get("messageId").asText());
        assertThat(coordinator.selectToRouter(1)).isEqualTo(1);
        var selected = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
        var message = selected.message();
        assertThat(selected.sources()).singleElement().satisfies(source -> assertThat(source.value()).isEqualTo(id));
        assertThat(message.serial).isEqualTo(id.toString());
        assertThat(message.owner_id).isEqualTo("account-one");
        assertThat(message.message_center).isNullOrEmpty();
        assertThat(message.body).isEqualTo("Hello + 10%");
        assertThat(message.priority).isEqualTo(2);
        assertThat(message.type).isEqualTo(StandardMessage.MSG_TEXT);
        assertThat(message.dcs).isEqualTo(StandardMessage.DCS_7BIT);
        assertThat(message.dlrReturnMetadata).isNull();
        coordinator.discard(selected.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
    }

    @Test
    void carriesUcs2PriorityAndCallbackMetadataWithoutHostPreapproval() throws Exception {
        var response = submit("""
                {"from":"Brand","to":"306900000000","text":"Καλημέρα","encoding":"ucs2",
                 "priority":3,"callbackUrl":"https://customer.example/receipts"}
                """);
        assertThat(response.statusCode()).isEqualTo(202);
        coordinator.selectToRouter(1);
        var selected = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
        var message = selected.message();
        assertThat(message.type).isEqualTo(StandardMessage.MSG_UCS2);
        assertThat(message.dcs).isEqualTo(StandardMessage.DCS_16BIT);
        assertThat(message.body).isEqualTo("Καλημέρα");
        assertThat(message.priority).isEqualTo(3);
        assertThat(message.acked).isTrue();
        assertThat(message.dlrReturnMetadata.accountId()).isEqualTo("account-one");
        assertThat(message.dlrReturnMetadata.forwardDlrUrl()).isEqualTo("https://customer.example/receipts");
        assertThat(message.dlrReturnMetadata.httpCallbackFormat()).isEqualTo(HttpCallbackFormat.JSON_POST);
        coordinator.discard(selected.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
    }

    @Test
    void returnsBackpressureWithoutAdmittingASecondSource() throws Exception {
        assertThat(submit(VALID).statusCode()).isEqualTo(202);
        assertError(submit(VALID), 503, "ADMISSION_UNAVAILABLE");
        assertThat(coordinator.selectToRouter(2)).isEqualTo(1);
    }

    @Test
    void acceptsGsmExtensionCharactersAndLowPriorityWithoutChangingText() throws Exception {
        String text = "Price €10 ^{}[]~|";
        String body = mapper.writeValueAsString(Map.of("from", "Brand", "to", "123", "text", text, "priority", 1));
        assertThat(submit(body).statusCode()).isEqualTo(202);
        coordinator.selectToRouter(1);
        var selected = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
        assertThat(selected.message().body).isEqualTo(text);
        assertThat(selected.message().priority).isEqualTo(1);
        assertThat(selected.message().dcs).isEqualTo(StandardMessage.DCS_7BIT);
        coordinator.discard(selected.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
    }

    @Test
    void rejectsSpoofedForwardingHeadersAndMissingAccountMetadata() throws Exception {
        useCredential("account-one", Set.of("198.51.100.1"));
        assertError(send(endpoint, VALID, "native-key", "X-Forwarded-For", "198.51.100.1",
                "Forwarded", "for=198.51.100.1;proto=https"), 403, "IP_NOT_ALLOWED");
        useCredential(null, Set.of());
        assertError(submit(VALID), 403, "ACCOUNT_NOT_CONFIGURED");
        assertThat(coordinator.selectToRouter(1)).isZero();
    }

    @Test
    void usesUpdatedAccountAndRevokedKeysOnSubsequentHttpRequests() throws Exception {
        useCredential("account-two", Set.of());
        assertThat(submit(VALID).statusCode()).isEqualTo(202);
        coordinator.selectToRouter(1);
        var selected = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
        assertThat(selected.message().owner_id).isEqualTo("account-two");
        coordinator.discard(selected.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
        when(credentials.getValidCredentials()).thenReturn(Map.of());
        assertError(submit(VALID), 401, "UNAUTHORIZED");
    }

    @Test
    void rejectsMissingAndWrongAuthenticationAndQueryParameters() throws Exception {
        assertError(send(endpoint, VALID, null), 401, "UNAUTHORIZED");
        assertError(send(endpoint, VALID, "wrong"), 401, "UNAUTHORIZED");
        assertError(send(URI.create(endpoint + "?account=another"), VALID, "native-key"), 400, "INVALID_REQUEST");
        assertThat(coordinator.selectToRouter(1)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{", "{}", "{} {}",
            "{\"from\":\"Sender\",\"to\":\"123\",\"text\":\"Hello\",\"account\":\"other\"}",
            "{\"from\":\"Sender\",\"to\":\"123\",\"text\":\"Hello\",\"smsc\":\"other\"}",
            "{\"from\":\"Sender\",\"to\":123,\"text\":\"Hello\"}",
            "{\"from\":\"Sender\",\"to\":\"123\",\"text\":\"Hello\",\"text\":\"Other\"}",
            "{\"from\":\"Sender\",\"to\":\"123\",\"text\":\"Hello\",\"priority\":1.5}",
            "{\"from\":\"Sender\",\"to\":\"123\",\"text\":\"Hello\",\"priority\":0}",
            "{\"from\":\"Sender\",\"to\":\"123\",\"text\":\"Hello\",\"priority\":4}",
            "{\"from\":\"Sender\",\"to\":\"123\",\"text\":\"Hello\",\"priority\":\"2\"}",
            "{\"from\":\"\",\"to\":\"123\",\"text\":\"Hello\"}",
            "{\"from\":\"Sender\",\"to\":\"not-a-number\",\"text\":\"Hello\"}",
            "{\"from\":\"Sender\",\"to\":\"123\",\"text\":\"\"}",
            "{\"from\":\"Sender\",\"to\":\"123\",\"text\":\"Καλημέρα\"}",
            "{\"from\":\"Sender\",\"to\":\"123\",\"text\":\"😀\",\"encoding\":\"ucs2\"}",
            "{\"from\":\"Sender\",\"to\":\"123\",\"text\":\"Hello\",\"encoding\":\"auto\"}",
            "{\"from\":\"Sender\",\"to\":\"123\",\"text\":\"Hello\",\"callbackUrl\":\"file:///tmp/receipt\"}",
            "{\"from\":\"Sender\",\"to\":\"123\",\"text\":\"Hello\",\"callbackUrl\":\"https://user:pass@example.com\"}"})
    void rejectsInvalidJsonAndFieldsBeforeAdmission(String body) throws Exception {
        assertError(submit(body), 400, "INVALID_REQUEST");
        assertThat(coordinator.selectToRouter(1)).isZero();
    }

    @Test
    void boundsMessageAndRequestSizeAndDoesNotEchoInvalidPayloads() throws Exception {
        var message = submit(VALID.replace("Hello + 10%", "x".repeat(4097)));
        assertError(message, 400, "INVALID_REQUEST");
        assertThat(message.body()).doesNotContain("x".repeat(100));
        assertError(submit(VALID.replace("Hello + 10%", "x".repeat(32768))), 413, "PAYLOAD_TOO_LARGE");
        assertThat(coordinator.selectToRouter(1)).isZero();
    }

    @Test
    void publishesJsonSchemasBearerSecurityAndTheCallbackContract() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(endpoint.resolve("/q/openapi?format=json")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var openapi = mapper.readTree(response.body());
            var operation = openapi.path("paths").path("/api/v1/messages").path("post");
            assertThat(operation.path("operationId").asText()).isEqualTo("submitNativeMessage");
            assertThat(operation.path("requestBody").path("content").path("application/json").path("schema").path("$ref").asText())
                    .isEqualTo("#/components/schemas/NativeMessageRequest");
            assertThat(operation.path("security").get(0).has("NativeApiKey")).isTrue();
            assertThat(openapi.path("components").path("securitySchemes").path("NativeApiKey").path("scheme").asText())
                    .isEqualTo("bearer");
            var schema = openapi.path("components").path("schemas").path("NativeMessageRequest");
            assertThat(schema.path("required").toString()).contains("from", "to", "text");
            assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
            assertThat(schema.path("properties").path("encoding").path("default").asText()).isEqualTo("gsm");
            assertThat(schema.path("properties").path("priority").path("default").asInt()).isEqualTo(2);
            assertThat(schema.path("properties").path("priority").path("maximum").asInt()).isEqualTo(3);
            var callback = operation.path("callbacks").path("deliveryReceipt").path("{$request.body#/callbackUrl}").path("post");
            assertThat(callback.path("requestBody").path("content").has("application/json")).isTrue();
            assertThat(callback.path("requestBody").path("content").path("application/json").path("schema")
                    .path("properties").path("status").path("enum").toString()).contains("delivered", "failed");
        }
    }

    @Test
    void returnsJsonForUnsupportedMethodsMediaTypesAndResponseFormats() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var method = client.send(HttpRequest.newBuilder(endpoint).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertError(method, 405, "METHOD_NOT_ALLOWED");
            assertThat(method.headers().firstValue("Allow").orElseThrow()).contains("POST");
            var media = client.send(HttpRequest.newBuilder(endpoint).header("Content-Type", "text/plain")
                    .header("Authorization", "Bearer native-key").POST(HttpRequest.BodyPublishers.ofString(VALID)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertError(media, 415, "UNSUPPORTED_MEDIA_TYPE");
            var accept = client.send(HttpRequest.newBuilder(endpoint).header("Content-Type", "application/json")
                    .header("Accept", "text/plain").header("Authorization", "Bearer native-key")
                    .POST(HttpRequest.BodyPublishers.ofString(VALID)).build(), HttpResponse.BodyHandlers.ofString());
            assertError(accept, 406, "NOT_ACCEPTABLE");
            assertThat(coordinator.selectToRouter(1)).isZero();
        }
    }

    private void useCredential(String account, Set<String> ips) {
        when(credentials.getValidCredentials()).thenReturn(Map.of("native-key", new CredentialFileWatcher.Credential(
                CredentialFileWatcher.CredentialType.HTTP, account, "product", null, null, "native-key", ips)));
    }

    private HttpResponse<String> submit(String body) throws Exception {
        return send(endpoint, body, "native-key");
    }

    private static HttpResponse<String> send(URI endpoint, String body, String key, String... extraHeaders) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            if (key != null) {
                request.header("Authorization", "Bearer " + key);
            }
            if (extraHeaders.length > 0) {
                request.headers(extraHeaders);
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("application/json");
        assertThat(mapper.readTree(response.body()).get("code").asText()).isEqualTo(code);
    }

    @Singleton
    public static class Beans {
        @Produces
        @Singleton
        OutboundCoordinator<StandardMessage> coordinator() {
            var pending = new MemoryPendingMessageStore<StandardMessage>(1, StandaloneMessageSnapshot::copy);
            var selected = new MemorySelectedRouterStore<>(pending, 1);
            var routed = new MemoryRoutedWorkStore<StandardMessage>(1, StandaloneMessageSnapshot::copy);
            var result = new DefaultOutboundCoordinator<>(pending, selected, routed);
            result.start();
            return result;
        }

        void close(@Disposes OutboundCoordinator<StandardMessage> coordinator) {
            coordinator.close();
        }

        @Produces
        @Singleton
        CredentialFileWatcher credentials() {
            return mock(CredentialFileWatcher.class);
        }
    }
}
