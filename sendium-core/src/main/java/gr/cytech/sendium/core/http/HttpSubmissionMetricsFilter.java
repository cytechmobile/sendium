package gr.cytech.sendium.core.http;

import gr.cytech.sendium.core.AccountSubmissionMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/** Counts one HTTP submission decision, including authentication and framework rejections. */
@Provider
public class HttpSubmissionMetricsFilter implements ContainerResponseFilter {
    private static final String COUNTED = HttpSubmissionMetricsFilter.class.getName() + ".counted";

    @Inject MeterRegistry metrics;

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        String path = request.getUriInfo().getPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        boolean submission = "GET".equals(request.getMethod()) &&
                ("sendsms".equals(path) || "sendsms/".equals(path)) ||
                "POST".equals(request.getMethod()) &&
                ("api/v1/messages".equals(path) || "api/v1/messages/".equals(path));
        if (!submission || Boolean.TRUE.equals(request.getProperty(COUNTED))) {
            return;
        }
        request.setProperty(COUNTED, true);
        metrics.counter("sendium.sms.submissions", "ingress", "http",
                "result", response.getStatus() == 202 ? "accepted" : "rejected").increment();
        if (response.getStatus() == 202 && request.getProperty(AccountSubmissionMetrics.HTTP_ACCOUNT) instanceof String accountId) {
            AccountSubmissionMetrics.accepted(metrics, "http", accountId);
        }
    }
}
