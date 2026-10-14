package gr.cytech.sendium.performance;

public record RunConfig(int schemaVersion, String ingressMode, double offeredMessagesPerSecond,
        int workerTps, int warmupSeconds, int measurementSeconds, int drainTimeoutSeconds,
        boolean dlrEnabled, int providerResponseDelayMillis, int providerDlrDelayMillis,
        int admissionTimeoutMillis, int maxOutstandingRequests, int maxTrackedMessages) {

    public RunConfig {
        if (schemaVersion != 1 || !("http".equals(ingressMode) || "smpp".equals(ingressMode))) {
            throw new IllegalArgumentException("Unsupported configuration version or ingress mode");
        }
        if (!Double.isFinite(offeredMessagesPerSecond) || offeredMessagesPerSecond <= 0
                || workerTps < 1 || warmupSeconds < 0 || measurementSeconds < 1
                || drainTimeoutSeconds < 0 || providerResponseDelayMillis < 0
                || providerDlrDelayMillis < 0 || admissionTimeoutMillis < 1
                || maxOutstandingRequests < 1 || maxTrackedMessages < 1) {
            throw new IllegalArgumentException("Invalid workload rate, duration, delay, or capacity");
        }
        if (maxOutstandingRequests > maxTrackedMessages) {
            throw new IllegalArgumentException("Request capacity exceeds correlation capacity");
        }
    }
}
