package gr.cytech.sendium.core.http;

import gr.cytech.sendium.core.AccountSubmissionMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpSubmissionMetricsFilterTest {
    @Test
    void acceptedApisAggregateTheSameAuthenticatedAccountWithoutCountingTwice() {
        var metrics = new SimpleMeterRegistry();
        try {
            var filter = new HttpSubmissionMetricsFilter();
            filter.metrics = metrics;
            var response = mock(ContainerResponseContext.class);
            when(response.getStatus()).thenReturn(202);
            for (var request : new ContainerRequestContext[]{request("GET", "sendsms"), request("POST", "api/v1/messages")}) {
                request.setProperty(AccountSubmissionMetrics.HTTP_ACCOUNT, "shared-account");
                filter.filter(request, response);
                filter.filter(request, response);
            }
            assertThat(metrics.get("sendium.sms.account.submissions").tags("accountId", "shared-account", "ingress", "http")
                    .counter().count()).isEqualTo(2);
            assertThat(metrics.get("sendium.sms.submissions").tag("result", "accepted").counter().count()).isEqualTo(2);
        } finally {
            metrics.close();
        }
    }

    @ParameterizedTest
    @CsvSource({"202,", "202,' '", "400,account", "401,account", "503,account"})
    void excludesMissingAccountsAndRejectedRequests(int status, String account) {
        var metrics = new SimpleMeterRegistry();
        try {
            var filter = new HttpSubmissionMetricsFilter();
            filter.metrics = metrics;
            var request = request("GET", "sendsms");
            request.setProperty(AccountSubmissionMetrics.HTTP_ACCOUNT, account);
            var response = mock(ContainerResponseContext.class);
            when(response.getStatus()).thenReturn(status);
            filter.filter(request, response);
            assertThat(metrics.find("sendium.sms.account.submissions").counters()).isEmpty();
        } finally {
            metrics.close();
        }
    }

    @ParameterizedTest
    @CsvSource({"GET,sendsms,202", "GET,/sendsms/,400", "POST,api/v1/messages,202",
            "POST,/api/v1/messages/,401", "POST,api/v1/messages,403", "POST,api/v1/messages,415",
            "GET,sendsms,503", "GET,sendsms,500"})
    void countsOneDecisionEvenIfInvokedAgain(String method, String path, int status) {
        var metrics = new SimpleMeterRegistry();
        try {
            var filter = new HttpSubmissionMetricsFilter();
            filter.metrics = metrics;
            var request = request(method, path);
            var response = mock(ContainerResponseContext.class);
            when(response.getStatus()).thenReturn(status);
            filter.filter(request, response);
            filter.filter(request, response);
            assertThat(metrics.get("sendium.sms.submissions").tag("ingress", "http")
                    .tag("result", status == 202 ? "accepted" : "rejected").counter().count()).isEqualTo(1);
            assertThat(metrics.getMeters()).hasSize(1);
        } finally {
            metrics.close();
        }
    }

    @ParameterizedTest
    @CsvSource({"GET,q/metrics", "GET,api/v1/messages", "POST,sendsms", "OPTIONS,api/v1/messages",
            "GET,sendsms/other", "POST,api/v1/messages/other"})
    void excludesNonSubmissionRoutesAndMethods(String method, String path) {
        var metrics = new SimpleMeterRegistry();
        try {
            var filter = new HttpSubmissionMetricsFilter();
            filter.metrics = metrics;
            filter.filter(request(method, path), mock(ContainerResponseContext.class));
            assertThat(metrics.getMeters()).isEmpty();
        } finally {
            metrics.close();
        }
    }

    private static ContainerRequestContext request(String method, String path) {
        var request = mock(ContainerRequestContext.class);
        var uri = mock(UriInfo.class);
        when(request.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn(path);
        when(request.getMethod()).thenReturn(method);
        var properties = new HashMap<String, Object>();
        when(request.getProperty(anyString())).thenAnswer(call -> properties.get(call.getArgument(0)));
        doAnswer(call -> {
            properties.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(request).setProperty(anyString(), any());
        return request;
    }
}
