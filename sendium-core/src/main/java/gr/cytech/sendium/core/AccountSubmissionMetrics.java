package gr.cytech.sendium.core;

import io.micrometer.core.instrument.MeterRegistry;

/** Accepted ingress traffic attributed only to an authenticated account identity. */
public final class AccountSubmissionMetrics {
    public static final String HTTP_ACCOUNT = AccountSubmissionMetrics.class.getName() + ".httpAccount";

    private AccountSubmissionMetrics() {
    }

    public static void accepted(MeterRegistry registry, String ingress, String accountId) {
        if (registry == null || accountId == null || accountId.isBlank()) {
            return;
        }
        try {
            registry.counter("sendium.sms.account.submissions", "ingress", ingress, "accountId", accountId).increment();
        } catch (RuntimeException ignored) {
            // Optional monitoring must not turn a successful admission into a processing failure.
        }
    }
}
