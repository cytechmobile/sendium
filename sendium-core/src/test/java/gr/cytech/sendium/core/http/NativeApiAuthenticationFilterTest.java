package gr.cytech.sendium.core.http;

import gr.cytech.sendium.auth.CredentialFileWatcher;
import gr.cytech.sendium.auth.CredentialFileWatcher.Credential;
import gr.cytech.sendium.auth.CredentialFileWatcher.CredentialType;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NativeApiAuthenticationFilterTest {
    @TempDir Path directory;
    private NativeApiAuthenticationFilter filter;
    private CredentialFileWatcher credentials;

    @BeforeEach
    void setUp() {
        credentials = mock(CredentialFileWatcher.class);
        filter = new NativeApiAuthenticationFilter();
        filter.credentials = credentials;
        filter.httpRequest = mock(HttpServerRequest.class);
        when(filter.httpRequest.remoteAddress()).thenReturn(SocketAddress.inetSocketAddress(1234, "192.0.2.1"));
        when(credentials.getValidCredentials()).thenReturn(Map.of("key-one", credential(Set.of())));
    }

    @Test
    void exposesOnlyAuthenticatedAccountAndProductAndPreservesTransportSecurity() {
        var request = request("bEaReR key-one");
        filter.filter(request);

        var context = ArgumentCaptor.forClass(SecurityContext.class);
        verify(request).setSecurityContext(context.capture());
        verify(request).setProperty(gr.cytech.sendium.core.AccountSubmissionMetrics.HTTP_ACCOUNT, "account-one");
        assertThat(context.getValue().getUserPrincipal()).isEqualTo(new NativeApiPrincipal("account-one", "product-one"));
        assertThat(context.getValue().getUserPrincipal().getName()).isEqualTo("account-one");
        assertThat(context.getValue().getUserPrincipal().toString()).doesNotContain("key-one");
        assertThat(context.getValue().isSecure()).isTrue();
        assertThat(context.getValue().getAuthenticationScheme()).isEqualTo("Bearer");
        assertThat(context.getValue().isUserInRole("admin")).isFalse();
        verify(request, never()).abortWith(any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Basic key-one", "Bearer", "Bearer ", "Bearer wrong", "Bearer key-one extra",
            "Bearer key-one,Bearer key-two", "Bearer key-one\n"})
    void rejectsMissingInvalidAndMalformedKeysWithTheSameSafeError(String header) {
        assertUnauthorized(header);
    }

    @Test
    void rejectsDuplicateAuthorizationHeaders() {
        var request = request("Bearer key-one");
        request.getHeaders().add(HttpHeaders.AUTHORIZATION, "Bearer key-one");
        filter.filter(request);
        assertError(request, 401, "UNAUTHORIZED");
    }

    @Test
    void doesNotAcceptSmppCredentialsOrUsernamePasswordLookupAsApiKeys() {
        when(credentials.getValidCredentials()).thenReturn(Map.of("key-one",
                new Credential(CredentialType.SMPP, "account", null, "key-one", "password", "key-one", null)));
        assertUnauthorized("Bearer key-one");
        when(credentials.getValidCredentials()).thenReturn(Map.of("key-one",
                new Credential(CredentialType.HTTP, "account", null, "key-one", "password", null, null)));
        assertUnauthorized("Bearer key-one");
        when(credentials.getValidCredentials()).thenReturn(Map.of("key-one",
                new Credential(CredentialType.HTTP, "account", null, null, null, " ", null)));
        assertUnauthorized("Bearer key-one");
    }

    @Test
    void requiresTheLookupToMatchTheCredentialApiKey() {
        when(credentials.getValidCredentials()).thenReturn(Map.of("alias", credential(null)));
        assertUnauthorized("Bearer alias");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsCredentialsWithoutAnAccount(String account) {
        when(credentials.getValidCredentials()).thenReturn(Map.of("key-one",
                new Credential(CredentialType.HTTP, account, null, null, null, "key-one", null)));
        var request = request("Bearer key-one");
        filter.filter(request);
        assertError(request, 403, "ACCOUNT_NOT_CONFIGURED");
    }

    @Test
    void rejectsAnUnapprovedAddressEvenWhenForwardedHeadersClaimAnApprovedAddress() {
        when(credentials.getValidCredentials()).thenReturn(Map.of("key-one", credential(Set.of("198.51.100.1"))));
        var request = request("Bearer key-one");
        request.getHeaders().putSingle("X-Forwarded-For", "198.51.100.1");
        request.getHeaders().putSingle("Forwarded", "for=198.51.100.1;proto=https");
        filter.filter(request);
        assertError(request, 403, "IP_NOT_ALLOWED");
    }

    @Test
    void permitsAnApprovedResolvedAddressAndUnrestrictedCredentials() {
        for (var credential : new Credential[] {credential(Set.of("192.0.2.1")), credential(Set.of()), credential(null)}) {
            when(credentials.getValidCredentials()).thenReturn(Map.of("key-one", credential));
            var request = request("Bearer key-one");
            filter.filter(request);
            verify(request).setSecurityContext(any());
            verify(request, never()).abortWith(any());
        }
    }

    @Test
    void successfulFileReloadChangesAccountAndKeyAndRemovalRevokesSubsequentRequests() throws Exception {
        filter.credentials = new CredentialFileWatcher();
        Path file = directory.resolve("credentials.yml");
        Files.writeString(file, """
                credentials:
                  - type: HTTP
                    apiKey: key-one
                    accountId: account-one
                """);
        reload(file);
        var first = request("Bearer key-one");
        filter.filter(first);
        verify(first).setSecurityContext(any());

        Files.writeString(file, """
                credentials:
                  - type: HTTP
                    apiKey: key-two
                    accountId: account-two
                """);
        reload(file);
        assertUnauthorized("Bearer key-one");
        var second = request("Bearer key-two");
        filter.filter(second);
        var context = ArgumentCaptor.forClass(SecurityContext.class);
        verify(second).setSecurityContext(context.capture());
        assertThat(context.getValue().getUserPrincipal().getName()).isEqualTo("account-two");

        Files.writeString(file, "credentials: []\n");
        reload(file);
        assertUnauthorized("Bearer key-two");
    }

    private void reload(Path file) throws Exception {
        var reload = CredentialFileWatcher.class.getDeclaredMethod("reloadCredentialConfiguration", File.class);
        reload.setAccessible(true);
        reload.invoke(filter.credentials, file.toFile());
    }

    private static Credential credential(Set<String> allowedIps) {
        return new Credential(CredentialType.HTTP, "account-one", "product-one", null, null, "key-one", allowedIps);
    }

    private static ContainerRequestContext request(String authorization) {
        var request = mock(ContainerRequestContext.class);
        var headers = new MultivaluedHashMap<String, String>();
        if (authorization != null) {
            headers.putSingle(HttpHeaders.AUTHORIZATION, authorization);
        }
        when(request.getHeaders()).thenReturn(headers);
        var security = mock(SecurityContext.class);
        when(security.isSecure()).thenReturn(true);
        when(request.getSecurityContext()).thenReturn(security);
        return request;
    }

    private void assertUnauthorized(String authorization) {
        var request = request(authorization);
        filter.filter(request);
        var response = assertError(request, 401, "UNAUTHORIZED");
        assertThat(response.getHeaderString(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(response.getEntity()).isEqualTo(new NativeApiError("UNAUTHORIZED", "A valid HTTP Bearer API key is required"));
    }

    private static Response assertError(ContainerRequestContext request, int status, String code) {
        var response = ArgumentCaptor.forClass(Response.class);
        verify(request).abortWith(response.capture());
        verify(request, never()).setSecurityContext(any());
        assertThat(response.getValue().getStatus()).isEqualTo(status);
        assertThat(response.getValue().getMediaType().toString()).isEqualTo("application/json");
        assertThat(((NativeApiError) response.getValue().getEntity()).code()).isEqualTo(code);
        return response.getValue();
    }
}
