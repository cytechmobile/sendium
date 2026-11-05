package gr.cytech.sendium.core.http;

import gr.cytech.sendium.auth.CredentialFileWatcher;
import gr.cytech.sendium.auth.CredentialFileWatcher.CredentialType;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;

import java.security.Principal;

/** Uses Quarkus's resolved remote address; forwarded headers are never parsed here. */
@Provider
@NativeApiAuthenticated
@Priority(Priorities.AUTHENTICATION)
public class NativeApiAuthenticationFilter implements ContainerRequestFilter {
    @Inject CredentialFileWatcher credentials;
    @Context HttpServerRequest httpRequest;

    @Override
    public void filter(ContainerRequestContext request) {
        var headers = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (headers == null || headers.size() != 1) {
            unauthorized(request);
            return;
        }
        String authorization = headers.getFirst();
        if (authorization == null || !authorization.matches("(?i)Bearer +[^\\s,]+")) {
            unauthorized(request);
            return;
        }
        String key = authorization.substring(authorization.indexOf(' ') + 1).stripLeading();
        // Resolve every request against the latest successfully reloaded snapshot, not a key cache.
        var credential = credentials.getValidCredentials().get(key);
        if (credential == null || credential.type() != CredentialType.HTTP ||
                credential.apiKey() == null || credential.apiKey().isBlank() || !key.equals(credential.apiKey())) {
            unauthorized(request);
            return;
        }
        String address = httpRequest.remoteAddress().hostAddress();
        if (!credential.isIpAllowed(address)) {
            request.abortWith(Response.status(Response.Status.FORBIDDEN).type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(new NativeApiError("IP_NOT_ALLOWED", "Client address is not allowed"))
                    .build());
            return;
        }
        if (credential.accountId() == null || credential.accountId().isBlank()) {
            request.abortWith(Response.status(Response.Status.FORBIDDEN).type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(new NativeApiError("ACCOUNT_NOT_CONFIGURED", "An accountId must be configured for this credential"))
                    .build());
            return;
        }
        var principal = new NativeApiPrincipal(credential.accountId(), credential.product());
        boolean secure = request.getSecurityContext().isSecure();
        request.setSecurityContext(new SecurityContext() {
            @Override
            public Principal getUserPrincipal() {
                return principal;
            }

            @Override
            public boolean isUserInRole(String role) {
                return false;
            }

            @Override
            public boolean isSecure() {
                return secure;
            }

            @Override
            public String getAuthenticationScheme() {
                return "Bearer";
            }
        });
    }

    private static void unauthorized(ContainerRequestContext request) {
        request.abortWith(Response.status(Response.Status.UNAUTHORIZED).type(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .entity(new NativeApiError("UNAUTHORIZED", "A valid HTTP Bearer API key is required"))
                .build());
    }
}
