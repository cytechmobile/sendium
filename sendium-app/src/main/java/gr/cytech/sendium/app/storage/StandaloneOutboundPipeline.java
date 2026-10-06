package gr.cytech.sendium.app.storage;

import gr.cytech.sendium.conf.SendiumConfigurationHandler;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.DefaultOutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundWork.WorkId;
import gr.cytech.sendium.core.smpp.client.SmppClientWorker;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.memory.MemoryPendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemoryRoutedWorkStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import gr.cytech.sendium.external.filter.FilterException;
import gr.cytech.sendium.routing.AbstractRoutingManager;
import gr.cytech.sendium.routing.OutgoingWorkerManager;
import gr.cytech.sendium.routing.RoutingLookupResult;
import gr.cytech.sendium.routing.StandardOutboundDispatch;
import gr.cytech.sendium.routing.StandardRoutingManager;
import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Vetoed;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Standalone-only assembly; importing sendium-core never starts these processors. */
@ApplicationScoped
public class StandaloneOutboundPipeline {
    private static final Logger logger = LoggerFactory.getLogger(StandaloneOutboundPipeline.class);
    @Inject Instance<OutboundCoordinator<StandardMessage>> coordinators;
    OutboundCoordinator<StandardMessage> coordinator;
    @Inject StandardRoutingManager routing;
    @Inject OutgoingWorkerManager workers;
    @Inject Config config;
    @Inject SendiumConfigurationHandler configuration;
    private StandardOutboundDispatch dispatch;
    private ScheduledExecutorService processors;
    private int batchSize;
    private final Map<WorkId, StandardOutboundDispatch.ProviderExecution> executions = new ConcurrentHashMap<>();

    @Produces
    @Singleton
    @DefaultBean
    static OutboundCoordinator<StandardMessage> coordinator(MessageStorageProfile profile, Config config) {
        int sourceCapacity = positive(config, "pending.capacity", 10000);
        positive(config, "selection-batch-size", 100);
        var pending = new MemoryPendingMessageStore<StandardMessage>(sourceCapacity,
                StandaloneMessageSnapshot::copy);
        var selected = new MemorySelectedRouterStore<>(pending, positive(config, "router-queue.capacity", 1000));
        var routed = new MemoryRoutedWorkStore<StandardMessage>(sourceCapacity,
                StandaloneMessageSnapshot::copy);
        var result = new DefaultOutboundCoordinator<>(pending, selected, routed);
        result.start();
        return result;
    }

    void start(@Observes @Priority(Interceptor.Priority.APPLICATION + 100) StartupEvent event) {
        if (coordinator == null) {
            coordinator = coordinators.get();
        }
        batchSize = positive(config, "selection-batch-size", 100);
        dispatch = new StandardOutboundDispatch(coordinator, new LifecycleRouting(routing));
        processors = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "standalone-outbound");
            thread.setDaemon(true);
            return thread;
        });
        processors.scheduleWithFixedDelay(this::process, 0, 100, TimeUnit.MILLISECONDS);
        logger.info("Standalone outbound memory pipeline activated");
    }

    void process() {
        try {
            retryExecutions();
            if (configuration.getBlnPrpt(AbstractRoutingManager._pause)) {
                return;
            }
            coordinator.selectAndStage(batchSize);
            for (int i = 0; i < batchSize; i++) {
                var selected = coordinator.takeForRouting(Duration.ZERO);
                if (selected.isEmpty()) {
                    break;
                }
                try {
                    dispatch.routeSelected(selected.orElseThrow());
                } catch (Exception failure) {
                    logFailure("routing", failure);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            logFailure("selection", failure);
        }
        try {
            dispatch.retryRoutingTransitions();
        } catch (Exception failure) {
            logFailure("routing transition", failure);
        }
        try {
            for (var worker : workers.getWorkersCopy()) {
                if (!(worker instanceof SmppClientWorker<?> client) || !worker.acceptsMessages() || worker.isPause()) {
                    continue;
                }
                for (int i = 0; i < batchSize; i++) {
                    var work = dispatch.takeForProvider(worker.getFullName(), Duration.ZERO);
                    if (work.isEmpty()) {
                        break;
                    }
                    try {
                        var execution = dispatch.submitToProvider(work.orElseThrow(), (SmppClientWorker<StandardMessage>) client);
                        executions.put(work.orElseThrow().id(), execution);
                    } catch (Exception failure) {
                        coordinator.returnToDestination(work.orElseThrow());
                        throw failure;
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            logFailure("provider dispatch", failure);
        }
    }

    private void retryExecutions() {
        executions.entrySet().removeIf(entry -> {
            var execution = entry.getValue();
            var completion = execution.completion().toCompletableFuture();
            if (completion.isDone() && !completion.isCompletedExceptionally()) {
                return true;
            }
            execution.retryPendingHandoffs();
            if (completion.isCompletedExceptionally()) {
                execution.retryTerminalCleanup();
            }
            return false;
        });
    }

    void stop(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) ShutdownEvent event) throws InterruptedException {
        if (dispatch == null) {
            return;
        }
        dispatch.quiesce();
        processors.shutdown();
        while (!processors.awaitTermination(1, TimeUnit.SECONDS)) {
            logger.warn("Waiting for standalone outbound processors to stop before ownership restoration");
        }
        boolean drained = false;
        int waits = 0;
        while (!drained) {
            try {
                retryExecutions();
                dispatch.retryRoutingTransitions();
                drained = dispatch.awaitProviderDrain(Duration.ofSeconds(1));
            } catch (RuntimeException failure) {
                logFailure("shutdown drain; retrying required handoffs and cleanup", failure);
                TimeUnit.SECONDS.sleep(1);
            }
            if (!drained && ++waits % 30 == 0) {
                logger.warn("Outbound shutdown still awaiting provider completion; workers and source ownership remain active");
            }
        }
        routing.stop();
        if (!workers.stop()) {
            throw new IllegalStateException("Worker shutdown incomplete; outbound stages remain open");
        }
        dispatch.close();
        coordinator.close();
        logger.info("Standalone outbound pipeline stopped after provider drain");
    }

    private static int positive(Config config, String suffix, int defaultValue) {
        String key = "sendium.message." + suffix;
        int value = config.getOptionalValue(key, Integer.class).orElse(defaultValue);
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return value;
    }

    private static void logFailure(String operation, Exception failure) {
        if (failure instanceof OutboundStorageException storage) {
            logger.error("Outbound {} failed; ownership retained stage={} reason={} detail={}",
                    operation, storage.stage(), storage.reason(), storage.getMessage());
        } else if (failure instanceof FilterException filter) {
            logger.warn("Outbound {} deferred by filter; ownership retained status={}", operation, filter.getStatusCode());
        } else {
            logger.error("Outbound {} failed; ownership retained failureType={}", operation, failure.getClass().getName());
        }
    }

    /** The activated provider boundary supports SMPP submission, not arbitrary legacy worker methods. */
    @Vetoed
    static final class LifecycleRouting extends StandardRoutingManager {
        private final StandardRoutingManager delegate;

        LifecycleRouting(StandardRoutingManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public RoutingLookupResult lookupForLifecycle(StandardMessage message) throws IOException {
            var result = delegate.lookupForLifecycle(message);
            if (result.getDestinations().stream().anyMatch(worker -> !(worker instanceof SmppClientWorker<?>))) {
                throw new OutboundStorageException(OutboundStage.Role.ROUTER_QUEUE,
                        OutboundStorageException.Reason.UNSUPPORTED,
                        "Standalone outbound execution requires an SMPP-client destination");
            }
            return result;
        }
    }
}
