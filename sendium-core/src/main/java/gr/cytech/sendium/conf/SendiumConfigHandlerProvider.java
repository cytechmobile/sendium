package gr.cytech.sendium.conf;

import io.quarkus.arc.All;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.util.List;

@Dependent
public class SendiumConfigHandlerProvider {

    @Inject @All
    List<SendiumConfigurationHandler> sendiumConfigurationHandlers;

    @Produces
    @DefaultBean
    public SendiumConfigurationProvider createSendiumConfigurationHandler() {
        return sendiumConfigurationHandlers.getFirst();
    }
}
