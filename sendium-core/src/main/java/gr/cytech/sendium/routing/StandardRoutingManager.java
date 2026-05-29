package gr.cytech.sendium.routing;

import com.google.common.base.Strings;
import gr.cytech.sendium.conf.SendiumConfigurationHandler;
import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.queue.InMemoryQueueProvider;
import gr.cytech.sendium.external.filter.FilterException;
import gr.cytech.sendium.util.MessageTrace;
import gr.cytech.sendium.util.TimeUtils;
import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
@DefaultBean
public class StandardRoutingManager extends AbstractRoutingManager<StandardMessage> implements RoutingChangeListener {
    private static final Logger logger = LoggerFactory.getLogger(StandardRoutingManager.class);
    @Inject
    SendiumConfigurationHandler sendiumConfigurationHandler;
    @Inject
    InMemoryQueueProvider queueProvider;

    @Inject
    OutgoingWorkerManager outgoingSmsHandler;

    @Inject
    RoutingFileWatcher routingFileWatcher;

    @PostConstruct
    public void init() {
        sendiumConfigurationHandler.loadDefaultParams(prms);
        pause = sendiumConfigurationHandler.getBlnPrpt(_pause);
        debugRouting = sendiumConfigurationHandler.getBlnPrpt(_debugRouting);
        failedq = new ConcurrentLinkedQueue<>();
        //set targets to null, it will be initialized after parsing new table
        targets = null;
    }

    public void startup(@Observes @Priority(Interceptor.Priority.APPLICATION) StartupEvent se) {
        if (routingFileWatcher.isRunning()) {
            routingFileWatcher.addRoutingChangeListener(this);
            start();
        } else {
            logger.info("Not started. RoutingFileWatcher is not running");
        }
    }

    @Override
    protected boolean getConfigBoolean(String[] prop) {
        return sendiumConfigurationHandler.getBlnPrpt(prop);
    }

    @Override
    protected int getConfigInt(String[] prop) {
        return sendiumConfigurationHandler.getIntPrpt(prop);
    }

    @Override
    protected String getConfigString(String[] prop) {
        return sendiumConfigurationHandler.getPrpt(prop);
    }

    public void beforeWorkerStop(AbstractOutWorker worker) {
        if (!areWorkersStarted) {
            return;
        }
        Collection<AbstractOutWorker> workers = outgoingSmsHandler.getWorkersCopy();
        workers.remove(worker);
        parseNewRoutingTable(routingFileWatcher.getUpdatedRoutes(), workers);
    }

    public void afterWorkerStart(AbstractOutWorker worker) {
        if (areWorkersStarted) {
            Collection<AbstractOutWorker> workers = outgoingSmsHandler.getWorkersCopy();
            workers.add(worker);
            parseNewRoutingTable(routingFileWatcher.getUpdatedRoutes(), workers);
        }
    }

    public void afterStartAll() {
        parseNewRoutingTable(routingFileWatcher.getUpdatedRoutes(), outgoingSmsHandler.getWorkersCopy());
        areWorkersStarted = true;
    }

    protected void start() {
        final Instant start = Instant.now();
        //make sure that we parse the routing table now
        parseNewRoutingTable(routingFileWatcher.getUpdatedRoutes(), outgoingSmsHandler.getWorkersCopy());
        restartExecutor();
        Instant end = Instant.now();
        logger.info("routing manager initialized in {}", Duration.between(start, end));
    }

    public void stop() {
        routingFileWatcher.removeRoutingChangeListener(this);
        keepOnRunning = false;
        restartExecutor();
        enqueueFailedToQueue();
    }

    @Override
    public void routingChange(Map<String, RoutingTable> updatedRoutes) {
        parseNewRoutingTable(updatedRoutes, outgoingSmsHandler.getWorkersCopy());
    }

    protected void parseNewRoutingTable(Map<String, RoutingTable> table, Collection<AbstractOutWorker> workers) {
        logger.info("Parsing new standard routing table");
        Map<String, RoutingTable> tmpTargets = new HashMap<>();
        try {
            if (table != null) {
                tmpTargets.putAll(table);
            } else {
                tmpTargets.putAll(routingFileWatcher.getUpdatedRoutes());
            }
            if (workers == null) {
                workers = outgoingSmsHandler.getWorkersCopy();
            }

            Map<String, AbstractOutWorker> workerTargets = new HashMap<>();
            for (var worker : workers) {
                String wrkrName = worker.getFullName();
                workerTargets.put(wrkrName, worker);
                var workerInstanceName = worker.getInstanceName();
                workerTargets.put(workerInstanceName, worker);
            }

            if (!tmpTargets.containsKey(RoutingFileParser.DEFAULT_ROUTING_TABLE_NAME) ||
                    tmpTargets.get(RoutingFileParser.DEFAULT_ROUTING_TABLE_NAME) == null ||
                    tmpTargets.get(RoutingFileParser.DEFAULT_ROUTING_TABLE_NAME).rules == null ||
                    tmpTargets.get(RoutingFileParser.DEFAULT_ROUTING_TABLE_NAME).rules.isEmpty()) {
                //maybe create a default table instead
                throw new Exception("no default routing table in new targets! " + tmpTargets);
            }
            targets = new RoutingTargets(tmpTargets, workerTargets);
            logger.info("Done parsing new routing table");
        } catch (Exception e) {
            logger.info("Error parsing new routing table", e);
        }
    }

    @Override
    protected StandardMessage getNextMessageToRoute() throws InterruptedException {
        var routerQueue = queueProvider.getRouterQueue();
        if (routerQueue != null) {
            return routerQueue.dequeue(200);
        }
        return null;
    }

    @Override
    protected void enqueueToRouterQueue(StandardMessage msg) throws InterruptedException {
        queueProvider.getRouterQueue().enqueue(msg);
    }

    private void handleMessageFailure(StandardMessage msg, Exception e) {
        if (msg != null) {
            if (msg.rtxCnt > 0) {
                TimeUtils.sleep(100, TimeUnit.MILLISECONDS);
            }
            msg.rtxCnt++;
        }
        logger.error("Error processing message {}", MessageTrace.identifiers(msg), e);
    }

    @Override
    protected void handleMessageUnexpectedFailure(StandardMessage msg, Throwable e) {
        if (msg != null) {
            failedq.add(msg);
            logger.error(
                    "Message ({}) has resulted in ERROR ({}). " +
                            "Adding it to failed queue! " +
                            "You must address the error and then re-enqueue the messages, " +
                            "by setting the property: {} equal to true!",
                    MessageTrace.identifiers(msg), e, _enqueueFailedRouting[0]);
            //sent error to external system ideally
        }
    }

    @Override
    protected RoutingLookupResult lookupRoutingForMessage(StandardMessage pMsg, RoutingTable table) throws IOException {
        if (pMsg == null || table == null || table.rules == null || table.rules.isEmpty()) {
            return RoutingLookupResult.EMPTY_RESULT;
        }
        var result = new RoutingLookupResult(new ArrayList<>(), false);
        final var originalNextTarget = pMsg.nextTarget;
        for (var route : table.rules) {

            if (!route.matches(pMsg, getTargets().getWorker(route.getTarget()))) {
                if (debugRouting) {
                    logger.info("Message: ({}) ! matches route: ({})", pMsg, route);
                }
                continue;
            }
            if (debugRouting) {
                logger.info("Message: ({})   matches route: ({})", pMsg, route);
            }
            result.mergeRoutingLookupResult(getRoutingLookupResultFromRouteForNormal(route, pMsg));

            if (result.hasReachedLast()) {
                if (pMsg.rtxCnt == 0 && !Strings.isNullOrEmpty(pMsg.nextTarget) && pMsg.nextTarget.equals(Strings.nullToEmpty(originalNextTarget))) {
                    logger.warn("Message has exact same next target as before routing. Switching it to noMoreTargets {}",
                            MessageTrace.identifiers(pMsg));
                    pMsg.nextTarget = NO_MORE_TARGETS;
                }
                return result;
            }

            if (debugRouting) {
                logger.info("No final target found in route:({})\nfor message:({})\ncontinuing with next route", route, pMsg);
            }
        }
        return result;
    }

    public RoutingLookupResult getRoutingLookupResultFromRouteForNormal(
            RoutingRule route, StandardMessage pMsg) throws IOException {
        final String targetName = route.getTarget();
        final var targetTable = getTargets().getTable(targetName);
        final var targetWorker = targetTable != null ? null : getTargets().getWorker(targetName);

        if (targetTable == null && targetWorker == null) {
            if (debugRouting) {
                logger.info("Route: ({}) points to a non-existing target: ({})", route, targetName);
            }
            return RoutingLookupResult.EMPTY_RESULT;
        }

        if (targetWorker != null) {
            if (targetWorker.isFilter()) {
                //Implement here the filter logic and continue
                applyFilterToMessage(targetWorker, pMsg);
                return RoutingLookupResult.EMPTY_RESULT;
            } else if (!targetWorker.acceptsMessages()) {
                if (debugRouting) {
                    logger.info("Route: ({}) points to a worker which does not (currently) accept messages", route);
                }
                return RoutingLookupResult.EMPTY_RESULT;
            }

            return new RoutingLookupResult(Collections.singletonList(targetWorker), !route.isCopied());
        }
        return lookupRoutingForMessage(pMsg, targetTable);
    }

    private void applyFilterToMessage(AbstractOutWorker filter, StandardMessage pMsg) throws IOException {
        try {
            filter.doFilter(AbstractOutWorker.FilterLifecyclePhase.DURING_ROUTING, pMsg);
        } catch (FilterException fe) {
            //We log here a debug message to inform the admin about the filtering
            if (logger.isDebugEnabled()) {
                logger.debug("Filter({})->{}({}): {}",
                        fe.getFilter(),
                        fe.getStatusCode().name(),
                        fe.getMessage(),
                        fe.getMessageObj());
            }
            switch (fe.getStatusCode()) {
                //When the filter either modifies some fields of the messages or
                //does not change any field must just let the messages go through
                //the upcoming destinations.
                case MODIFIED:
                case UNCHANGED:
                    return;
                //The drop,re-enqueue, re-schedule and unknown policy is handled
                //by the caller
                case DROP:
                case REENQUEUE:
                case RESCHEDULE:
                case UNKNOWN:
                default:
                    throw fe;
            }
        }
    }

    public RoutingTargets getTargets() {
        return targets;
    }

    public void beforeStopAll() {
        areWorkersStarted = false;
    }

}
