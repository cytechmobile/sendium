package smsgateway.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;

@Provider
@SecuredAsAdmin
@ApplicationScoped
public class SecuredAsAdminFilter implements ContainerRequestFilter {

    @Inject ApiKeyFilter apiKeyFilter;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        apiKeyFilter.filterAsAdmin(requestContext);
    }
}
