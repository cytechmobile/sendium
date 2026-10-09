package gr.cytech.sendium.app.storage;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class StandaloneMessageStorage {
    private static final Logger logger = LoggerFactory.getLogger(StandaloneMessageStorage.class);

    @Produces
    @Singleton
    MessageStorageProfile profile(Config config) {
        try {
            return new MessageStorageProfile(backend(config, MessageStorageProfile.PENDING_BACKEND),
                    backend(config, MessageStorageProfile.ROUTER_BACKEND),
                    backend(config, MessageStorageProfile.ROUTED_BACKEND));
        } catch (IllegalArgumentException failure) {
            // Startup can exit before asynchronous log handlers flush. Selectors contain no message payload.
            System.err.println("Outbound message storage startup failed: " + failure.getMessage());
            throw failure;
        }
    }

    // The observed parameter registers early startup validation/logging; its payload is not needed.
    void start(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) StartupEvent event, MessageStorageProfile profile) {
        logger.info("Outbound message storage profile (pending/router-queue/routed-work): {}", profile);
        logger.warn("NON-DURABLE outbound message storage: accepted messages and in-flight work can be lost on process restart.");
    }

    private static String backend(Config config, String name) {
        ConfigValue property = config.getConfigValue(name);
        // An absent setting uses the default; an explicitly empty setting must fail validation.
        return property.getRawValue() == null ? "memory" : property.getValue();
    }
}
