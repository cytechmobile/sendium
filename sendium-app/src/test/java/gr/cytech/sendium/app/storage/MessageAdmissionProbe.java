package gr.cytech.sendium.app.storage;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class MessageAdmissionProbe {
    @Inject
    MessageStorageProfile profile;

    private MessageStorageProfile profileAtStartup;

    // The observed parameter registers the startup callback; the event payload is not needed.
    void start(@Observes @Priority(Interceptor.Priority.APPLICATION) StartupEvent event) {
        profileAtStartup = profile;
        LoggerFactory.getLogger(MessageAdmissionProbe.class).info("test admission startup reached");
    }

    public MessageStorageProfile profileAtStartup() {
        return profileAtStartup;
    }
}
