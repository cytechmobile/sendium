package gr.cytech.sendium.external;

import gr.cytech.sendium.auth.CredentialFileWatcher;
import gr.cytech.sendium.core.queue.InMemoryQueueProvider;
import gr.cytech.sendium.core.queue.QueueProvider;
import gr.cytech.sendium.core.smpp.client.SmppClientHolder;
import gr.cytech.sendium.core.worker.ForwardMoService;
import gr.cytech.sendium.core.worker.InMemoryDlrService;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
@DefaultBean
public class WorkerResourceProvider {
    private static final Logger logger = LoggerFactory.getLogger(WorkerResourceProvider.class);

    public enum Visibility { INTERNAL, EXTERNAL }

    @Inject InMemoryQueueProvider queueProvider;
    @Inject CredentialFileWatcher  credentialFileWatcher;
    @Inject InMemoryDlrService dlrService;
    @Inject ForwardMoService forwardMoService;
    @Inject SmppClientHolder smppClientHolder;

    public WorkerResourceProvider() {
    }

    public DataSource getDataSource() {
        return null;
    }

    public CredentialFileWatcher getCredentialFileWatcher() {
        return credentialFileWatcher;
    }

    public InMemoryDlrService getDlrService() {
        return dlrService;
    }

    public ForwardMoService getForwardMoService() {
        return forwardMoService;
    }

    public SmppClientHolder getSmppClientHolder() {
        return smppClientHolder;
    }

    public void registerHealthCheckReporter(HealthCheckReporter healthCheckReporter) {
    }

    public void unregisterHealthCheckReporter(HealthCheckReporter healthCheckReporter) {
    }

    public boolean checkIfWorkerExists(String workerFullName) {
        return false;
    }

    public String charMappingOut(String charMapperName, String in) {
        return in;
    }

    public String charMappingIn(String charMapperName, String in) {
        return in;
    }

    public QueueProvider geQueueProvider() {
        return queueProvider;
    }

    public void notifyError(Visibility visibility, String errorMessage, Object... msgArgs) {
        logger.error(errorMessage);
    }

    public boolean stopExecutor(ExecutorService executor, Logger errorLogger, String name) {
        if (executor == null || executor.isTerminated()) {
            return true;
        }
        executor.shutdown();
        try {
            return executor.awaitTermination(1, TimeUnit.MINUTES);
        } catch (Exception e) {
            (errorLogger != null ? errorLogger : logger).warn("error waiting for {} executor to shutdown", name, e);
        }
        return false;
    }
}