package smsgateway.smpp;

import com.cloudhopper.smpp.impl.DefaultSmppClient;
import io.netty.channel.nio.NioEventLoopGroup;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import smsgateway.providers.LogProvider;

@ApplicationScoped
public class SmppClientHolder {
    private static final Logger logger =
            LogProvider.getSmppClientLogger(SmppClientHolder.class.getName());

    protected DefaultSmppClient smppClient;
    private ScheduledExecutorService enquireLinkExecutor; // New field

    public DefaultSmppClient resource() {
        return smppClient;
    }

    void onStart(@Observes StartupEvent ev) {
        var workerGroup =
                new NioEventLoopGroup(
                        4,
                        Thread.ofPlatform()
                                .name("SmppClientEngine-Worker-", 1)
                                .uncaughtExceptionHandler(
                                        (t, e) ->
                                                logger.warn(
                                                        "SmppClientEngineWorker|error|uncaught-exception",
                                                        e))
                                .factory());
        var monitor =
                Executors.newScheduledThreadPool(
                        1,
                        Thread.ofVirtual()
                                .name("SmppClientEngine-Monitor-", 1)
                                .uncaughtExceptionHandler(
                                        (t, e) ->
                                                logger.warn(
                                                        "SmppClientEngineMonitor|error|uncaught-exception",
                                                        e))
                                .factory());
        smppClient = new DefaultSmppClient(workerGroup, monitor);

        // Initialize the new enquireLinkExecutor
        this.enquireLinkExecutor =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofVirtual()
                                .name("EnquireLinkScheduler-", 1)
                                .uncaughtExceptionHandler(
                                        (t, e) ->
                                                logger.warn(
                                                        "EnquireLinkScheduler|error|uncaught-exception",
                                                        e))
                                .factory());

        logger.info("started SMPP Client Engine and EnquireLinkScheduler");
    }

    void onStop(@Observes ShutdownEvent ev) {
        closeEngine(smppClient);
        smppClient = null;

        // Shutdown the enquireLinkExecutor
        if (this.enquireLinkExecutor != null) {
            logger.info("Shutting down EnquireLinkScheduler...");
            this.enquireLinkExecutor.shutdown();
            try {
                if (!this.enquireLinkExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn(
                            "EnquireLinkScheduler did not terminate gracefully after 5 seconds. Forcing shutdown...");
                    this.enquireLinkExecutor.shutdownNow();
                } else {
                    logger.info("EnquireLinkScheduler shut down gracefully.");
                }
            } catch (InterruptedException e) {
                logger.warn(
                        "Interrupted while waiting for EnquireLinkScheduler to shut down. Forcing shutdown...");
                this.enquireLinkExecutor.shutdownNow();
                Thread.currentThread().interrupt(); // Preserve interrupt status
            }
        }
        logger.info("Stopped SMPP Client Engine");
    }

    public ScheduledExecutorService getEnquireLinkExecutor() {
        return this.enquireLinkExecutor;
    }

    public static void closeEngine(DefaultSmppClient old) {
        if (old == null) {
            return;
        }
        old.destroy(0, 1);
    }
}
