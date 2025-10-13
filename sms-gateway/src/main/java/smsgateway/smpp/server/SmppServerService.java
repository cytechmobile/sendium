package smsgateway.smpp.server;

import com.cloudhopper.smpp.SmppServerConfiguration;
import com.cloudhopper.smpp.impl.DefaultSmppServer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.slf4j.Logger;
import smsgateway.auth.ApiKeyService;
import smsgateway.providers.LogProvider;

@ApplicationScoped
public class SmppServerService {
    private static final Logger logger =
            LogProvider.getSmppServerLogger(SmppServerService.class.getName());

    private final SmppServerConfig config;
    private final SmppServerHandler smppServerHandler;
    private final ApiKeyService apiKeyService; // Added ApiKeyService
    private DefaultSmppServer smppServer;
    private NioEventLoopGroup bossGroup; // For Netty
    private NioEventLoopGroup workerGroup; // For Netty
    private ScheduledThreadPoolExecutor monitorExecutor; // For request expiry monitoring

    @Inject
    public SmppServerService(
            SmppServerConfig config,
            SmppServerHandler smppServerHandler,
            ApiKeyService apiKeyService) { // Added ApiKeyService
        this.config = config;
        this.smppServerHandler = smppServerHandler;
        this.apiKeyService = apiKeyService; // Store injected ApiKeyService
    }

    void onStart(@Observes StartupEvent ev) {
        if (config.enabled()) {
            logger.info("Starting SMPP Server...");
            bossGroup =
                    new NioEventLoopGroup(
                            1, Thread.ofPlatform().name("SmppServerBossPool-", 1).factory());
            // worker group can generally be larger, e.g., number of cores
            workerGroup =
                    new NioEventLoopGroup(
                            0, Thread.ofPlatform().name("SmppServerWorkerPool-", 1).factory());

            monitorExecutor =
                    (ScheduledThreadPoolExecutor)
                            Executors.newScheduledThreadPool(
                                    1,
                                    Thread.ofVirtual()
                                            .name("SmppServerSessionWindowMonitorPool-", 1)
                                            .factory());

            SmppServerConfiguration serverConfig = new SmppServerConfiguration();
            serverConfig.setHost(config.host());
            serverConfig.setPort(config.port());

            // handler
            serverConfig.setMaxConnectionSize(config.maxConnections());

            // API changes based on demo and expected errors
            serverConfig.setDefaultRequestExpiryTimeout(config.defaultSessionTimeoutMs());
            serverConfig.setBindTimeout(
                    config.defaultSessionTimeoutMs()); // Using same as session timeout for bind
            // phase
            serverConfig.setDefaultWindowSize(
                    10); // Default window size from demo, can be made configurable
            serverConfig.setDefaultWindowMonitorInterval(15000); // From demo
            serverConfig.setNonBlockingSocketsEnabled(true); // From demo
            serverConfig.setDefaultSessionCountersEnabled(true); // From demo
            serverConfig.setJmxEnabled(false); // Disabled JMX for now, can be made configurable

            // Updated constructor based on demo
            this.smppServer =
                    new DefaultSmppServer(
                            serverConfig,
                            smppServerHandler,
                            monitorExecutor,
                            bossGroup,
                            workerGroup);

            try {
                logger.info(
                        "Attempting to start SMPP server on {}:{}", config.host(), config.port());
                smppServer.start();
                logger.info("SMPP Server started on {}:{}", config.host(), config.port());
            } catch (Exception e) {
                logger.error("Failed to start SMPP Server", e);
            }
        } else {
            logger.info("SMPP Server is disabled in configuration.");
        }
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (smppServer != null && smppServer.isStarted()) {
            logger.info("Stopping SMPP Server...");
            smppServer.destroy();
            logger.info("SMPP Server stopped.");
        }
        // Shutdown Netty event loop groups and monitor executor
        if (monitorExecutor != null) {
            monitorExecutor.shutdownNow();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    // Alternative using @PreDestroy for bean destruction
    // @javax.annotation.PreDestroy
    // public void destroy() {
    //     if (smppServer != null && smppServer.isStarted()) {
    //         logger.info("Destroying SMPP Server (PreDestroy)...");
    //         smppServer.destroy();
    //         logger.info("SMPP Server destroyed (PreDestroy).");
    //     }
    // }
}
