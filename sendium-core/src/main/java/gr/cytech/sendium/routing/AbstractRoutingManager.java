package gr.cytech.sendium.routing;

import gr.cytech.sendium.conf.PropertyChangeEvent;
import gr.cytech.sendium.conf.PropertyChangeListener;
import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.external.filter.FilterException;
import gr.cytech.sendium.util.MessageTrace;
import gr.cytech.sendium.util.TimeUtils;
import io.quarkus.arc.Arc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public abstract class AbstractRoutingManager<M extends StandardMessage> implements PropertyChangeListener {
    public static final String NO_MORE_TARGETS = "noMoreTargets";
    public static final String[][] prms = {
            {"outSms.routing.debug", "false"}
            , {"outSms.pause", "false"}
            , {"outSms.routing.threads", "1"}
            , {"outSms.printFailedRouting", "false"}
            , {"outSms.enqueueFailedRouting", "false"}
    };
    public static final String[] _debugRouting = prms[0];
    public static final String[] _pause = prms[1];
    public static final String[] _routingThreads = prms[2];
    public static final String[] _printFailedRouting = prms[3];
    public static final String[] _enqueueFailedRouting = prms[4];

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected ExecutorService routingExecutor;
    protected List<Router> routerJobs;
    protected volatile boolean areWorkersStarted = false;
    protected ConcurrentLinkedQueue<M> failedq = new ConcurrentLinkedQueue<>();
    protected volatile boolean keepOnRunning = true;
    protected volatile int routingExecutorVersion;
    protected boolean debugRouting;
    protected boolean pause;
    protected RoutingTargets targets;

    protected abstract M getNextMessageToRoute() throws InterruptedException;

    protected abstract void enqueueToRouterQueue(M msg) throws InterruptedException;

    protected abstract RoutingLookupResult lookupRoutingForMessage(M pMsg, RoutingTable table) throws IOException;

    protected abstract void handleMessageUnexpectedFailure(M msg, Throwable e);

    protected abstract boolean getConfigBoolean(String[] prop);

    protected abstract int getConfigInt(String[] prop);

    protected abstract String getConfigString(String[] prop);

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        String key = evt.getKey();
        if (key.equals(_debugRouting[0])) {
            debugRouting = getConfigBoolean(_debugRouting);
        } else if (key.equals(_pause[0])) {
            pause = getConfigBoolean(_pause);
        } else if (key.equals(_routingThreads[0])) {
            restartExecutor();
        } else if (key.equals(_printFailedRouting[0])) {
            if (getConfigBoolean(_printFailedRouting)) {
                logger.warn("Failed messages count: {}", failedq.size());
                for (M m : failedq) {
                    logger.debug("{}", m);
                }
            }
        } else if (key.equals(_enqueueFailedRouting[0])) {
            if (getConfigBoolean(_enqueueFailedRouting)) {
                logger.debug("Re-enqueueing failed messages after receiving enqueueFailed property command");
                enqueueFailedToQueue();
            }
        }
    }

    protected synchronized void restartExecutor() {
        routingExecutorVersion++;
        if (routingExecutor != null) {
            routingExecutor.shutdown();
            try {
                boolean ok = routingExecutor.awaitTermination(10, TimeUnit.SECONDS);
                if (!ok) {
                    logger.warn("routing executor threads did not terminate gracefully within 10 seconds");
                }
            } catch (Exception e) {
                logger.warn("error waiting for routing executor termination", e);
            }
            routingExecutor = null;
        }
        if (!keepOnRunning) {
            return;
        }
        int routingThreads = getConfigInt(_routingThreads);
        final List<Router> newRouters = new ArrayList<>(routingThreads);
        routingExecutor = Executors.newFixedThreadPool(routingThreads, Thread.ofPlatform().name("Router-" + routingExecutorVersion + "-", 1).factory());
        for (int i = 0; i < routingThreads; i++) {
            var router = new Router(routingExecutorVersion);
            routingExecutor.submit(router);
            newRouters.add(router);
        }
        routerJobs = newRouters;
    }

    public void enqueueFailedToQueue() {
        if (failedq.isEmpty()) {
            return;
        }
        while (!failedq.isEmpty()) {
            var m = failedq.poll();
            if (m == null) {
                break;
            }
            try {
                enqueueToRouterQueue(m);
            } catch (Exception e) {
                TimeUtils.sleep(100, TimeUnit.MILLISECONDS);
                logger.warn("error enqeueuing failed message to router queue", e);
                failedq.add(m);
            }
        }
    }

    protected void reEnqueueMessage(M msg) {
        while (msg != null) {
            try {
                enqueueToRouterQueue(msg);
                msg = null;
            } catch (InterruptedException ie) {
                // ignore it. we will retry
            }
        }
    }

    public void getNextMessageInQueueAndRoute() {
        M msg = null;
        try {
            msg = getNextMessageToRoute();
            if (msg == null) {
                return;
            }

            if (pause) {
                reEnqueueMessage(msg);
                msg = null;
                return;
            }
            RoutingLookupResult result = lookupRoutingForMessage(msg, targets.defaultTable);
            boolean sent = sendMessageToTargets(msg, result);
            if (sent) {
                msg = null; //Avoid further handling in finally block;
            } else {
                if (MessageTrace.shouldLog(getConfigString(MessageTrace.TRACE_MODE), MessageTrace.EVENT_ROUTING_MISS)) {
                    logger.warn("message.routing.miss action=requeue {}", MessageTrace.identifiers(msg));
                }
                TimeUtils.sleep(100, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException ie) {
            logger.warn("Interrupted while processing message {}", MessageTrace.identifiers(msg), ie);
        } catch (FilterException fe) {
            switch (fe.getStatusCode()) {
                case DROP:
                    if (MessageTrace.shouldLog(getConfigString(MessageTrace.TRACE_MODE), MessageTrace.EVENT_DROPPED)) {
                        logger.info("message.dropped filter={} reason={} {}", fe.getFilter().getFullName(), fe.getMessage(),
                                MessageTrace.identifiers(msg));
                    }
                    msg = null;
                    break;
                case RESCHEDULE:
                    logger.warn("RESCHEDULE status code is not yet implemented. Falling back to REENQUEUE.");
                    break;
                case RETRY:
                    if (msg != null) {
                        msg.rtxCnt++;
                    }
                    break;
                case REENQUEUE:
                    break;
                case UNKNOWN:
                    logger.warn("UNKNOWN status code received. Falling back to REENQUEUE.");
                    break;
                default:
                    logger.warn("Invalid status code {}. Falling back to REENQUEUE.", fe.getStatusCode());
            }
        } catch (Exception e) {
            handleMessageFailure(msg, e);
            if (msg != null && msg.rtxCnt > 50) {
                handleMessageUnexpectedFailure(msg, e);
                msg = null;
            }
        } catch (Throwable e) {
            handleMessageUnexpectedFailure(msg, e);
            msg = null;
        } finally {
            reEnqueueMessage(msg);
        }
    }

    @SuppressWarnings("unchecked")
    public boolean sendMessageToTargets(M msg, RoutingLookupResult result) throws InterruptedException {
        int destCnt = result.getDestinations().size();
        if (destCnt > 0) {
            for (var target : result.getDestinations()) {
                if (debugRouting) {
                    logger.info("Message: ({}) has target: ({})", msg, target);
                }
                // Suppressing unchecked cast since we know workers take StandardMessage
                if (MessageTrace.shouldLog(getConfigString(MessageTrace.TRACE_MODE), MessageTrace.EVENT_ROUTED)) {
                    logger.info("message.routed target={} {}", target.getFullName(), MessageTrace.identifiers(msg));
                }
                ((AbstractOutWorker<M>) target).enqueue(msg);
            }
            return true;
        }
        return false;
    }

    private void handleMessageFailure(M msg, Exception e) {
        if (msg != null) {
            if (msg.rtxCnt > 0) {
                TimeUtils.sleep(100, TimeUnit.MILLISECONDS);
            }
            msg.rtxCnt++;
        }
        logger.error("Error processing message {}", MessageTrace.identifiers(msg), e);
    }

    public static class RoutingTargets {
        public final Map<String, RoutingTable> routingTables;
        public final Map<String, AbstractOutWorker> workers;
        public final RoutingTable defaultTable;

        public RoutingTargets(Map<String, RoutingTable> tables, Map<String, AbstractOutWorker> workers) {
            this.routingTables = tables;
            this.workers = workers;
            // Assumes both environments name the default table "default"
            this.defaultTable = routingTables.get("default");
        }

        public RoutingTable getTable(String target) {
            return routingTables.get(target);
        }

        public AbstractOutWorker getWorker(String target) {
            return workers.get(target);
        }
    }

    public class Router implements Runnable {
        public final int version;
        public boolean currentRouterPaused;

        public Router(int version) {
            this.version = version;
            this.currentRouterPaused = true;
        }

        @Override
        public void run() {
            while (keepOnRunning && !areWorkersStarted) {
                TimeUtils.sleep(1, TimeUnit.SECONDS);
            }

            currentRouterPaused = false;
            while (keepOnRunning && version == routingExecutorVersion) {
                if (pause) {
                    logger.info("_PAUSING_ PROCESSING....");
                    currentRouterPaused = true;
                    do {
                        TimeUtils.sleep(1, TimeUnit.SECONDS);
                    } while (pause && keepOnRunning);
                    if (!pause) {
                        currentRouterPaused = false;
                        logger.info("_RESUMING_ PROCESSING....");
                    }
                    continue;
                }
                try {
                    Arc.container().requestContext().activate();
                    getNextMessageInQueueAndRoute();
                } finally {
                    Arc.container().requestContext().terminate();
                }
            }
            logger.debug("_STOP_ PROCESSING....");
        }
    }
}
