package gr.cytech.sendium.routing;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import gr.cytech.sendium.conf.PropertyChangeEvent;
import gr.cytech.sendium.conf.PropertyChangeListener;
import gr.cytech.sendium.conf.SendiumConfigurationHandler;
import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.queue.InMemoryQueueProvider;
import gr.cytech.sendium.core.smpp.server.InMemorySmppServerMessageStore;
import gr.cytech.sendium.core.smpp.server.SmppServerWorker;
import gr.cytech.sendium.core.worker.InMemoryMessageTracker;
import gr.cytech.sendium.core.worker.WorkerType;
import gr.cytech.sendium.external.WorkerResourceProvider;
import gr.cytech.sendium.external.filter.InMessageFiltering;
import gr.cytech.sendium.util.TimeUtils;
import io.quarkus.arc.ClientProxy;
import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Typed;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
@DefaultBean
@Typed(OutgoingWorkerManager.class)
public class StandardOutgoingWorkerHandler implements PropertyChangeListener, OutgoingWorkerManager {
    private static final Logger logger = LoggerFactory.getLogger(StandardOutgoingWorkerHandler.class);

    @Inject
    @Any
    Instance<AbstractOutWorker<StandardMessage>> availableWorkers;
    @Inject
    SendiumConfigurationHandler configurationHandler;
    @Inject
    InMemoryQueueProvider queueProvider;
    @Inject
    WorkerResourceProvider workerResourceProvider;
    @Inject
    StandardRoutingManager routingManager;
    @Inject
    OutgoingWorkerManager activeManager;

    private Map<String, AbstractOutWorker> outSmsWorkers;
    private Pattern instancePattern;
    private Matcher instanceMatcher;
    private Matcher filtersMatcher;

    public StandardOutgoingWorkerHandler() {
        outSmsWorkers = new ConcurrentHashMap<>();
    }

    @PostConstruct
    public void init() {
        outSmsWorkers = new ConcurrentHashMap<>(128);
        instancePattern = Pattern.compile("outSms.instance.([^.]*).enable");
        instanceMatcher = instancePattern.matcher("");
        filtersMatcher = Pattern.compile("outSms.instance.([^.]*).filters.([^.]*)").matcher("");
    }

    public void startup(@Observes @Priority(Interceptor.Priority.APPLICATION) StartupEvent se) {
        Object actualActiveInstance = ClientProxy.unwrap(activeManager);
        if (actualActiveInstance != this) {
            logger.info("extended OutgoingWorkerManager detected. Skipping standard startup.");
            return;
        }
        configurationHandler.addPropertyChangeListener(this);
        startAllWorkersSync();
    }

    void onStop(@Observes ShutdownEvent ev) {
        stop();
    }

    public boolean stop() {
        configurationHandler.removePropertyChangeListener(this);
        stopAllWorkers();
        return true;
    }

    @Override
    public void propertyChange(PropertyChangeEvent propertyChangeEvent) {
        String key = propertyChangeEvent.getKey();
        if (instanceMatcher.reset(key).matches()) {
            startStopWorker(instanceMatcher.group(1), Boolean.parseBoolean(propertyChangeEvent.getNewValue()));
            // re-configure here the filters lists for all the instances
            parseAndSetFiltersListsFor(null);
        } else if (filtersMatcher.reset(key).matches()) {
            // re-configure here the filters lists for the specific instance
            List<String> workers = new ArrayList<>();
            workers.add(filtersMatcher.group(1));
            parseAndSetFiltersListsFor(workers);
        }
    }

    protected void startAllWorkersSync() {
        final Instant start = Instant.now();
        searchAndStartAllInstances();
        notifyAfterStartAll();
        Instant end = Instant.now();
        logger.info("initialized in {}", Duration.between(start, end));
    }

    private void searchAndStartAllInstances() {
        List<String> startWorkersList = getAllEnabledWorkersFromConfiguration();

        try (var ex = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("StandardOutgoingWorkerHandler-Start-", 1).factory())) {
            // start the instances
            List<Future<?>> startFutures = new ArrayList<>(startWorkersList.size());
            for (String workerName : startWorkersList) {
                startFutures.add(ex.submit(() -> startStopWorker(workerName, true)));
            }
            //now make sure all workers are started
            for (int i = 0; i < startFutures.size(); i++) {
                var f = startFutures.get(i);
                try {
                    f.get(60000, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    logger.warn("error when getting result for starting worker: {}", startWorkersList.get(i), e);
                }
            }
        }
        // configure the filters lists
        parseAndSetFiltersListsFor(startWorkersList);
    }

    private List<String> getAllEnabledWorkersFromConfiguration() {
        List<String> enabledWorkers = new ArrayList<>();
        var confKeys = configurationHandler.getAllKeysReadOnly();
        // search for the instances
        //Use new matcher instance to avoid thread issues (pattern.matcher(CharSequence) is thread safe)
        Matcher matcher = instancePattern.matcher("");
        for (String key : confKeys) {
            if (matcher.reset(key).matches()) {
                if (configurationHandler.getBlnPrpt(key)) {
                    enabledWorkers.add(matcher.group(1));
                }
            }
        }
        return enabledWorkers;
    }

    private void startStopWorker(String instName, boolean enable) {
        logger.info("starting:{} worker {} in thread {}", enable, instName, Thread.currentThread().getName());
        AbstractOutWorker worker = outSmsWorkers.get(instName);
        if (enable) {
            if (worker == null) {
                var type = configurationHandler.getPrpt("outSms.instance." + instName + ".type");
                if (Strings.isNullOrEmpty(type)) {
                    logger.error("Cannot start worker '{}': Missing '.type' property in configuration.", instName);
                    return;
                }
                var winst = startWorker(instName, type);
                if (winst != null) {
                    outSmsWorkers.put(instName, winst);
                }
            } else {
                logger.warn("worker: <{}> already running", instName);
            }
        } else {
            if (worker == null) {
                logger.warn("worker: <{}> is unknown", instName);
            } else {
                stopWorker(worker);
            }
        }
    }

    protected AbstractOutWorker<StandardMessage> startWorker(String instName, String workerTypeString) {
        AbstractOutWorker<StandardMessage> worker;
        try {
            WorkerTypeLiteral literal = new WorkerTypeLiteral(workerTypeString.toLowerCase());
            Instance<AbstractOutWorker<StandardMessage>> selectedWorker = availableWorkers.select(literal);
            if (selectedWorker.isUnsatisfied()) {
                logger.error("No worker implementation found for type: {}", workerTypeString);
                return null;
            }
            worker = selectedWorker.get();
            worker.setupInstance(configurationHandler, instName, queueProvider.getRouterQueue());
            worker.init(workerResourceProvider, new InMemoryMessageTracker(worker));
            if (SmppServerWorker.TYPE_SMPP_SERVER.equals(worker.getType())) {
                var smppServer = (SmppServerWorker<StandardMessage>) worker;
                smppServer.setMessageStore(new InMemorySmppServerMessageStore(smppServer));
            }
        } catch (Exception e) {
            logger.error("Could not start worker {} {}", workerTypeString, instName, e);
            return null;
        }

        logger.info("Starting: ({})", worker.getFullName()); //
        worker.start();

        if (worker.isKeepOnRunning()) {
            notifyAfterWorkerStart(worker);
            return worker;
        }

        logger.warn("Worker did NOT start: ({})", worker.getFullName()); //
        return null;
    }

    protected void stopAllWorkers() {
        logger.info("Shutting down workers");
        notifyBeforeStopAll();
        stopWorkers();
    }

    private void stopWorkers() {
        try (var ex = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("StandardOutgoingWorkerHandler-Stop-", 1).factory())) {
            List<Future<?>> stopFutures = new ArrayList<>(outSmsWorkers.size());
            List<String> stopWorkers = new ArrayList<>(outSmsWorkers.size());
            for (AbstractOutWorker worker : outSmsWorkers.values()) {
                stopWorkers.add(worker.getFullName());
                stopFutures.add(ex.submit(() -> stopWorker(worker)));
            }

            for (int i = 0; i < stopFutures.size(); i++) {
                Future<?> f = stopFutures.get(i);
                try {
                    f.get(60000, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    logger.warn("exception while trying to stop worker: {}", stopWorkers.get(i), e);
                }
            }
        }
    }

    private void notifyBeforeStopAll() {
        try {
            routingManager.beforeStopAll();
        } catch (Throwable t) {
            logger.warn("notification for beforeStopAll failed", t);
        }
    }

    private void notifyAfterStartAll() {
        try {
            routingManager.afterStartAll();
        } catch (Throwable t) {
            logger.warn("OutgoingSmsHandler notification for afterStartAll failed", t);
        }
    }

    private void stopWorker(AbstractOutWorker worker) {
        final long start = System.currentTimeMillis();
        logger.debug("stopping worker {} in thread {}", worker.getFullName(), Thread.currentThread().getName());
        notifyBeforeWorkerStop(worker);
        logger.info("Stopping: ({})", worker.getFullName());
        try {
            worker.stop();
        } catch (Exception e) {
            logger.warn("exception stopping worker: {}", worker.getFullName(), e);
        }
        outSmsWorkers.remove(worker.getInstanceName());
        worker.dequeueAllToRouter();
        logger.debug("stopping worker {} in thread {} took: {}ms",
                worker.getFullName(), Thread.currentThread().getName(), System.currentTimeMillis() - start);
    }

    private void parseAndSetFiltersListsFor(List<String> workers) {
        if (workers == null) {
            //If the workers list is null we update all workers
            workers = getAllEnabledWorkersFromConfiguration();
        }

        //configure the filters lists in order
        for (String workerName : workers) {
            //Get the worker and check if there are any filters defined
            AbstractOutWorker worker = outSmsWorkers.get(workerName);
            //Filters cannot have their own filters
            if (worker != null && !worker.isFilter()) {
                worker.setBeforeDoMsgFilters(parseFilters(worker, worker.getBeforeDoMsgFiltersList()));
                worker.setAfterDoMsgSuccessFilters(parseFilters(worker, worker.getAfterDoMsgSuccessFiltersList()));
                worker.setAfterDoMsgFailureFilters(parseFilters(worker, worker.getAfterDoMsgFailureFiltersList()));

                if (worker instanceof InMessageFiltering imf) {
                    imf.setBeforeInsertMessageFilters(parseFilters(worker, imf.getBeforeInsertMessageFiltersList()));
                }
            }
        }
    }

    private List<AbstractOutWorker> parseFilters(AbstractOutWorker worker, String filtersProperty) {
        List<AbstractOutWorker> filters = new ArrayList<>();
        try {
            List<String> filtersInstanceNames = getFilterInstanceNamesFromFiltersDefinition(filtersProperty);
            for (String filterName : filtersInstanceNames) {
                try {
                    AbstractOutWorker filter = outSmsWorkers.get(filterName);
                    if (filter == null) {
                        logger.warn("Set Filters ({}): The filter {} was IGNORED because it's not defined.",
                                worker.getFullName(), filterName);
                    } else if (!filter.isFilter()) {
                        logger.warn("Set Filters ({}): The {} was IGNORED because it's not a filter.",
                                worker.getFullName(), filterName);
                    } else {
                        //Add the filter in the list
                        filters.add(filter);
                        logger.debug("Set Filters ({}): The filter {} was added.",
                                worker.getFullName(), filter.getFullName());
                    }
                } catch (Exception ex) {
                    logger.warn("Set Filters ({}): The {} was IGNORED because of exception.",
                            worker.getFullName(), filterName, ex);
                }
            }
        } catch (Exception ex) {
            logger.warn("Set Filters ({}): All filters IGNORED.", worker.getFullName(), ex);
        }

        return filters.isEmpty() ? null : filters;
    }

    private List<String> getFilterInstanceNamesFromFiltersDefinition(String definition) {
        if (Strings.isNullOrEmpty(definition)) {
            return List.of();
        }

        //Stream the definition line (e.g. " filter_type.name, filter_type.name2, filter_type2.name3 ")
        //and split it using the ',' as separator (produces a String[] -a Stream of 1 String[])
        //re-stream the generated String[] (instead of continuing with 1 element -being out String[]- we make it a new stream of Strings)
        //trim each String, to remove whitespace
        //then split each one using the '.' as separator (produces a String[] for each String in the Stream -a Stream of String[]s-)
        //using filter we keep only those String[]s that have 2 elements (e.g. {"filter_type","name"})
        //then keep only the 2nd element (the name of the filter)
        //and finally store it in a list
        return Arrays.stream(definition.split(",")).map(String::trim)
                .map(filterFullName -> filterFullName.split("\\."))
                .filter(splitVals -> splitVals.length == 2)
                .map(splitVal -> splitVal[1])
                .collect(Collectors.toList());
    }

    private void notifyBeforeWorkerStop(AbstractOutWorker worker) {
        try {
            routingManager.beforeWorkerStop(worker);
        } catch (Throwable t) {
            logger.warn("notification for beforeWorkerStop({}) failed", worker, t);
        }
    }

    private void notifyAfterWorkerStart(AbstractOutWorker worker) {
        try {
            routingManager.afterWorkerStart(worker);
        } catch (Throwable t) {
            logger.warn("notification for afterWorkerStart({}) failed", worker, t);
        }
    }

    public Collection<AbstractOutWorker> getWorkersCopy() {
        while (true) {
            try {
                return Lists.newArrayList(outSmsWorkers.values());
            } catch (Exception e) {
                logger.warn("error getting copy of out workers", e);
                TimeUtils.sleep(500, TimeUnit.MILLISECONDS);
            }
        }
    }

    public Map<String, AbstractOutWorker> getWorkers() {
        return outSmsWorkers;
    }

    // Helper class required by CDI to perform dynamic string lookups on Annotations
    public static class WorkerTypeLiteral extends AnnotationLiteral<WorkerType> implements WorkerType {
        private final String value;

        public WorkerTypeLiteral(String value) {
            this.value = value;
        }

        @Override
        public String value() {
            return value;
        }
    }
}
