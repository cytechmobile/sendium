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
public class StandaloneSmsStorage {
    private static final Logger logger = LoggerFactory.getLogger(StandaloneSmsStorage.class);

    @Produces
    @Singleton
    SmsStorageProfile profile(Config config) {
        return new SmsStorageProfile(backend(config, SmsStorageProfile.PENDING_BACKEND),
                backend(config, SmsStorageProfile.ROUTER_BACKEND),
                backend(config, SmsStorageProfile.ROUTED_BACKEND));
    }

    void start(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) StartupEvent event, SmsStorageProfile profile) {
        logger.info("Outbound SMS storage profile (pending/router-queue/routed-work): {}", profile);
        logger.warn("NON-DURABLE outbound SMS storage: accepted messages and in-flight work can be lost on process restart.");
    }

    private static String backend(Config config, String name) {
        ConfigValue property = config.getConfigValue(name);
        // An absent setting uses the default; an explicitly empty setting must fail validation.
        return property.getRawValue() == null ? "memory" : property.getValue();
    }
}
