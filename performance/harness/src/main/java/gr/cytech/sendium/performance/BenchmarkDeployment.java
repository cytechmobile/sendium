package gr.cytech.sendium.performance;

import java.net.URI;

/** Fully resolved deployment/preset input. Database credentials are private runtime input. */
public record BenchmarkDeployment(String runIdentity, String preset, String sendiumHost, String harnessHost,
        int httpPort, int smppPort, int providerPort, int callbackPort,
        int ingressWindow, int outgoingWindow, int providerWindow, int providerMaxPending,
        int connectTimeoutMillis, int outgoingRequestTimeoutMillis, int providerIoTimeoutMillis,
        int outgoingWorkerThreads, int callbackThreads, int callbackQueueCapacity, Database database) {
    public BenchmarkDeployment {
        if (runIdentity == null || !runIdentity.matches("[A-Za-z0-9-]{1,32}")
                || preset == null || preset.isBlank() || database == null) {
            throw new IllegalArgumentException("Missing deployment identity, preset, or database");
        }
        host(sendiumHost);
        host(harnessHost);
        for (int port : new int[]{httpPort, smppPort, providerPort, callbackPort}) {
            if (port < 1 || port > 65535) { throw new IllegalArgumentException("Invalid benchmark port"); }
        }
        for (int value : new int[]{ingressWindow, outgoingWindow, providerWindow, providerMaxPending,
                connectTimeoutMillis, outgoingRequestTimeoutMillis, providerIoTimeoutMillis,
                outgoingWorkerThreads, callbackThreads, callbackQueueCapacity}) {
            if (value < 1) { throw new IllegalArgumentException("Invalid benchmark resource bound"); }
        }
        if (httpPort == smppPort || providerPort == callbackPort
                || sendiumHost.equals(harnessHost) && (httpPort == providerPort || httpPort == callbackPort
                    || smppPort == providerPort || smppPort == callbackPort)) {
            throw new IllegalArgumentException("Benchmark listeners have conflicting ports");
        }
    }

    private static void host(String host) {
        try {
            if (host == null || !host.matches("[A-Za-z0-9.:-]+")
                    || new URI("http", null, host, 8080, "/", null, null).getHost() == null) {
                throw new IllegalArgumentException("Invalid benchmark host");
            }
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid benchmark host");
        }
    }

    public record Database(String jdbcUrl, String username, String password,
            int poolMinSize, int poolMaxSize, int acquisitionTimeoutMillis, int claimDurationSeconds) {
        public Database {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")
                    || username == null || username.isBlank() || password == null || password.isEmpty()
                    || poolMinSize < 0 || poolMaxSize < 1 || poolMinSize > poolMaxSize
                    || acquisitionTimeoutMillis < 1 || claimDurationSeconds < 1) {
                throw new IllegalArgumentException("Invalid benchmark PostgreSQL configuration");
            }
            URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getFragment() != null
                    || uri.getPath() == null || uri.getPath().length() < 2) {
                throw new IllegalArgumentException("PostgreSQL URL must identify a host and isolated database");
            }
            if (uri.getRawQuery() != null) {
                for (String parameter : uri.getRawQuery().split("&")) {
                    String key = java.net.URLDecoder.decode(parameter.split("=", 2)[0], java.nio.charset.StandardCharsets.UTF_8);
                    if (java.util.Set.of("user", "password", "sslpassword").contains(key.toLowerCase(java.util.Locale.ROOT))) {
                        throw new IllegalArgumentException("PostgreSQL credentials must be supplied separately");
                    }
                }
            }
        }
        @Override public String toString() { return "Database[credentials=redacted]"; }
    }
}
