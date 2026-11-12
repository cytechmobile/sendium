package gr.cytech.sendium.core;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountSubmissionMetricsTest {
    @Test
    void sharedAccountsAggregateAcrossCredentialsAndRemainSeparatedByIngress() {
        var registry = new SimpleMeterRegistry();
        try {
            AccountSubmissionMetrics.accepted(registry, "http", "shared");
            AccountSubmissionMetrics.accepted(registry, "http", "shared");
            AccountSubmissionMetrics.accepted(registry, "smpp", "shared");
            AccountSubmissionMetrics.accepted(registry, "http", "other");
            assertThat(registry.get("sendium.sms.account.submissions").tags("accountId", "shared", "ingress", "http")
                    .counter().count()).isEqualTo(2);
            assertThat(registry.get("sendium.sms.account.submissions").tags("accountId", "shared", "ingress", "smpp")
                    .counter().count()).isEqualTo(1);
            assertThat(registry.getMeters()).hasSize(3);
        } finally {
            registry.close();
        }
    }

    @Test
    void exportsOnlyAccountAndIngressLabelsAndEscapesConfiguredIdentity() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            AccountSubmissionMetrics.accepted(registry, "http", "account\"\\\n");
            assertThat(registry.scrape()).contains("sendium_sms_account_submissions_total", "ingress=\"http\"",
                    "accountId=\"account\\\"\\\\\\n\"").doesNotContain("password=", "systemId=", "messageId=");
        } finally {
            registry.close();
        }
    }

    @Test
    void ignoresMissingIdentityAndRegistryFailures() {
        var registry = new SimpleMeterRegistry();
        try {
            AccountSubmissionMetrics.accepted(registry, "http", null);
            AccountSubmissionMetrics.accepted(registry, "smpp", " ");
            assertThat(registry.getMeters()).isEmpty();
        } finally {
            registry.close();
        }
        AccountSubmissionMetrics.accepted(null, "http", "account");
        var broken = mock(MeterRegistry.class);
        when(broken.counter(anyString(), any(String[].class))).thenThrow(new IllegalStateException("unavailable"));
        assertThatCode(() -> AccountSubmissionMetrics.accepted(broken, "http", "account")).doesNotThrowAnyException();
    }
}
