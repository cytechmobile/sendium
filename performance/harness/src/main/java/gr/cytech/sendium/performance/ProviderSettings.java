package gr.cytech.sendium.performance;

/** Deployment-only settings; credentials never belong in benchmark results. */
public record ProviderSettings(String host, int port, String systemId, String password,
        int maxConnections, int windowSize, int maxPending, int ioTimeoutMillis) {
    public ProviderSettings {
        if (host == null || host.isBlank() || port < 1 || port > 65535
                || systemId == null || systemId.isBlank() || password == null || password.isEmpty()
                || maxConnections < 1 || windowSize < 1 || maxPending < 1 || ioTimeoutMillis < 1) {
            throw new IllegalArgumentException("Invalid provider deployment settings");
        }
    }

    @Override public String toString() {
        return "ProviderSettings[host=" + host + ", port=" + port + ", credentials=redacted]";
    }
}
