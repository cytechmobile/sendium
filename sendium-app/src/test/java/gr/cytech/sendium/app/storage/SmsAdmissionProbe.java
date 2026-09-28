package gr.cytech.sendium.app.storage;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class SmsAdmissionProbe {
    @Inject
    SmsStorageProfile profile;

    private SmsStorageProfile profileAtStartup;

    void start(@Observes @Priority(Interceptor.Priority.APPLICATION) StartupEvent event) {
        profileAtStartup = profile;
        LoggerFactory.getLogger(SmsAdmissionProbe.class).info("test admission startup reached");
    }

    public SmsStorageProfile profileAtStartup() {
        return profileAtStartup;
    }
}
