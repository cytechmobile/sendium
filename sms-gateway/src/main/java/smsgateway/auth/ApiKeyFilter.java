package smsgateway.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

@Provider
@ApplicationScoped
public class ApiKeyFilter implements ContainerRequestFilter {
    public static final String API_KEY_HEADER = "X-API-Key";

    @Inject ApiKeyService apiKeyService;

    @Override
    @ServerRequestFilter(preMatching = true)
    public void filter(ContainerRequestContext requestContext) throws IOException {
        // This is a workaround to apply the filter to specific annotations
        // as Quarkus RESTEasy Reactive doesn't support @NameBinding on filters directly yet.
        // We will check the annotations on the resource method in the filter logic.
    }

    // This method will be called by a new filter for @SecuredAsMessageSender
    public void filterAsMessageSender(ContainerRequestContext requestContext) {
        String providedKey = requestContext.getHeaderString(API_KEY_HEADER);
        boolean valid = apiKeyService.isMessageKeyValid(providedKey);
        if (!valid) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED).build());
        }
    }

    // This method will be called by a new filter for @SecuredAsAdmin
    public void filterAsAdmin(ContainerRequestContext requestContext) {
        String providedKey = requestContext.getHeaderString(API_KEY_HEADER);
        boolean valid = apiKeyService.isAdminKeyValid(providedKey);
        if (!valid) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED).build());
        }
    }
}
