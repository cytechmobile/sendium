package gr.cytech.sendium.performance;

public record SmppIngressSettings(String host, int port, String systemId, String password,
        String source, String destination, int windowSize, int connectTimeoutMillis) {
    public SmppIngressSettings {
        if (host == null || host.isBlank() || port < 1 || port > 65535 || systemId == null
                || systemId.isBlank() || password == null || password.isEmpty()
                || source == null || source.isBlank() || destination == null || destination.isBlank()
                || windowSize < 1 || connectTimeoutMillis < 1) {
            throw new IllegalArgumentException("Invalid SMPP ingress settings");
        }
    }
    @Override public String toString() { return "SmppIngressSettings[credentials=redacted]"; }
}
