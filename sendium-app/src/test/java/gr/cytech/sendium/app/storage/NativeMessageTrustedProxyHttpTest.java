package gr.cytech.sendium.app.storage;

import gr.cytech.sendium.auth.CredentialFileWatcher;
import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class NativeMessageTrustedProxyHttpTest {
    @RegisterExtension
    static final QuarkusExtensionTest APP = NativeMessageHttpTest.application()
            .overrideConfigKey("quarkus.http.proxy.proxy-address-forwarding", "true")
            .overrideConfigKey("quarkus.http.proxy.allow-forwarded", "false")
            .overrideConfigKey("quarkus.http.proxy.allow-x-forwarded", "true")
            .overrideConfigKey("quarkus.http.proxy.trusted-proxies", "127.0.0.1,::1");

    @TestHTTPResource("/api/v1/messages") URI endpoint;
    @Inject CredentialFileWatcher credentials;

    @Test
    void usesTheClientAddressSuppliedByAnExplicitlyTrustedProxy() throws Exception {
        when(credentials.getValidCredentials()).thenReturn(Map.of("native-key", new CredentialFileWatcher.Credential(
                CredentialFileWatcher.CredentialType.HTTP, "account", null, null, null, "native-key", Set.of("203.0.113.9"))));
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(endpoint).header("Authorization", "Bearer native-key")
                    .header("Content-Type", "application/json").header("X-Forwarded-For", "203.0.113.9")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"from\":\"Brand\",\"to\":\"123\",\"text\":\"Hello\"}"));
            assertThat(client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(202);
            // An untrusted client address remains forbidden even when an unenabled header claims an allowed one.
            request.setHeader("X-Forwarded-For", "203.0.113.10").header("Forwarded", "for=203.0.113.9");
            assertThat(client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        }
    }
}
