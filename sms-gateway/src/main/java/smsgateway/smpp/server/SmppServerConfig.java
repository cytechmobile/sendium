package smsgateway.smpp.server;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "gateway.smpp.server")
public interface SmppServerConfig {
    @WithDefault("false")
    boolean enabled();

    @WithDefault("0.0.0.0")
    String host();

    @WithDefault("2775")
    int port();

    @WithDefault("10")
    int maxConnections();

    @WithDefault("30000")
    long defaultSessionTimeoutMs();
}
