package gr.cytech.sendium.core;

import com.google.common.base.Strings;
import com.google.common.util.concurrent.RateLimiter;
import gr.cytech.sendium.conf.PropertyChangeEvent;
import gr.cytech.sendium.conf.PropertyChangeListener;
import gr.cytech.sendium.conf.SendiumConfigurationProvider;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.queue.Queue;
import gr.cytech.sendium.core.worker.DefaultFailDelayPolicy;
import gr.cytech.sendium.core.worker.FailDelayPolicy;
import gr.cytech.sendium.core.worker.FailDelayPolicyAction;
import gr.cytech.sendium.core.worker.Tracker;
import gr.cytech.sendium.external.HealthCheckCode;
import gr.cytech.sendium.external.HealthCheckReport;
import gr.cytech.sendium.external.HealthCheckReporter;
import gr.cytech.sendium.external.NoOpVendorKpiHandler;
import gr.cytech.sendium.external.VendorKpiHandler;
import gr.cytech.sendium.external.WorkerResourceProvider;
import gr.cytech.sendium.external.filter.FilterException;
import gr.cytech.sendium.util.MessageTrace;
import gr.cytech.sendium.util.Sleeper;
import gr.cytech.sendium.util.StatsKeeper;
import gr.cytech.sendium.util.TimeUtils;
import io.quarkus.arc.Arc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public abstract class AbstractOutWorker<M extends StandardMessage> implements HealthCheckReporter {

    public final String[][] prms = {
            {"maxRetries", "1"} // 0 == unlimited
            , {"threadCount", "1"}
            , {"debug", "false"}
            , {"pause", "false"}
            , {"suspend", "false"}
            , {"print.msgs", "false"}
            , {"queue.honourPriorities", "false"}
            , {"queue.name", ""}
            , {"tps", "0"} //Transactions Per Second
            , {"filters.beforeDoMessage", ""}
            , {"filters.afterDoMessageSuccess", ""}
            , {"filters.afterDoMessageFailure", ""}
            , {"alert.maxPending", "0"} // unlimited
            , {"alert.maxRejected", "0"} // unlimited
            , {"alert.maxQueueSize", "0"} // unlimited
            , {"fail.action.worker.type", isMessageHandlingSynchronous() ? "SLEEP" : "RE_ENQUEUE_WORKER"}
            , {"fail.action.router.type", "RE_ENQUEUE_ROUTER"}
            // How many secs to sleep before trying the message again (internally, to avoid busy loop in case worker is not properly set or is not behaving ok)
            , {"fail.action.worker.sleep", isMessageHandlingSynchronous() ? "1000" : "0"}
            // How many secs to sleep before routing message to router (to avoid busy loop in case routing is not properly set)
            , {"fail.action.router.sleep", isMessageHandlingSynchronous() ? "2000" : "0"}
            , {"fail.action.delayed.delay", "5000"} //After how many millis the message entering the delay q will be able to be sent again
            , {"pause.sleep.ms", "1000"}
            , {"charmapper.enabled", "true"}
            , {"charmapper", ""}
            , {"suspension.policy", SuspensionPolicy.SUSPEND.name()}
            , {"suspension.stopMessages.ms", "-1"}
            , {"suspension.disable.ms", "-1"}
            , {"kpi.enabled", "false"}
            , {"kpi.period.minutes", "60"} //max 120 minutes
            , {"kpi.volume", "100"}
            , {"kpi.fail.statuses", ""}
    };
    public final DelayQueue<DelayedMessage<M>> delayQ = new DelayQueue<>();
    public final ConcurrentMap<Integer, Integer> failedMsgCounter = new ConcurrentHashMap<>();
    public int maxRetries;
    public int threadCount;
    public Sleeper sleeper;
    public boolean kpiEnabled = false;
    public volatile boolean keepOnRunning;
    public boolean printMsgs;
    public Logger logger;
    public HealthCheckReport healthCheckReport;

    protected ExecutorService executor;
    protected SendiumConfigurationProvider configurationProvider;
    protected boolean debug;
    protected Tracker<M> messageTracker;

    protected boolean pause;
    protected boolean suspendManually;
    protected boolean suspendAuto;

    protected SuspensionPolicy suspensionPolicy;
    protected long suspensionStart;
    protected boolean suspensionStopAcceptingMessages;
    protected ScheduledExecutorService suspensionMonitorExecutor;

    protected VendorKpiHandler vendorKpiHandler;
    protected WorkerResourceProvider workerResources;

    protected StatsKeeper stats;

    private int prmsIndex = 0;
    public final String[] _maxRetries = prms[prmsIndex++];
    protected final String[] _threadCount = prms[prmsIndex++];
    protected final String[] _debug = prms[prmsIndex++];
    public final String[] _pause = prms[prmsIndex++];
    public final String[] _suspendManually = prms[prmsIndex++];
    protected final String[] _printMsgs = prms[prmsIndex++];
    public final String[] _queuePriority = prms[prmsIndex++];
    public final String[] _queueName = prms[prmsIndex++];
    protected final String[] _tps = prms[prmsIndex++];
    protected final String[] _filtersBeforeDoMsg = prms[prmsIndex++];
    protected final String[] _filtersAfterDoMsgSuccess = prms[prmsIndex++];
    protected final String[] _filtersAfterDoMsgFailure = prms[prmsIndex++];
    public final String[] _alertMaxPending = prms[prmsIndex++];
    public final String[] _alertMaxRejected = prms[prmsIndex++];
    public final String[] _alertMaxQueueSize = prms[prmsIndex++];
    public final String[] _failActionWorkerType = prms[prmsIndex++];
    public final String[] _failActionRouterType = prms[prmsIndex++];
    public final String[] _failActionWorkerSleep = prms[prmsIndex++];
    public final String[] _failActionRouterSleep = prms[prmsIndex++];
    public final String[] _failActionDelayedDelay = prms[prmsIndex++];
    public final String[] _pauseSleepMs = prms[prmsIndex++];
    protected final String[] _charmapperEnabled = prms[prmsIndex++];
    protected final String[] _charmapperName = prms[prmsIndex++];

    public final String[] _suspensionPolicy = prms[prmsIndex++];
    public final String[] _suspensionStopMessagesAfter = prms[prmsIndex++];
    public final String[] _suspensionDisableAfter = prms[prmsIndex++];
    public final String[] _kpiEnabled = prms[prmsIndex++];
    public final String[] _kpiPeriod = prms[prmsIndex++];
    public final String[] _kpiVolume = prms[prmsIndex++];
    public final String[] _kpiFailStatuses = prms[prmsIndex++];

    private Queue<M> msgQ;
    /**
     * The queue of the router, used to directly enqueue messages to the router, via {@link #enqueueToRouter}
     */
    private Queue<M> routerQueue;
    private String instanceName;
    private long alertMaxPendingQueue;
    private long alertMaxRejectedQueue;
    private long alertMaxQueueSize;
    private PropertyChangeListener propertyChangeListener;
    private String fullName;
    private final Thread delayQDrainer = Thread.ofVirtual().unstarted(new Runnable() {
        @Override
        public void run() {
            Thread.currentThread().setName(getFullName() + "_delayQDrainer");
            while (keepOnRunning) {
                DelayedMessage<M> dMsg = null;
                try {
                    dMsg = delayQ.poll(10, TimeUnit.SECONDS);
                    if (dMsg != null && dMsg.getMessage() != null) {
                        enqueue((M) dMsg.getMessage());
                    }
                    dMsg = null;
                } catch (InterruptedException e) {
                    //Not really anything to do
                    logger.debug("Thread interrupted");
                } catch (Exception ex) {
                    logger.warn("Error re-enqueuing delayed message", ex);
                } finally {
                    if (dMsg != null) {
                        saveMessage((M) dMsg.getMessage());
                    }
                }
            }
        }

        private void saveMessage(final M msg) {
            if (msg == null) {
                logger.debug("No message to save");
            }
            try {
                enqueue(msg);
            } catch (Exception ex) {
                try {
                    msgQ.enqueue(msg);
                } catch (Exception e1) {
                    try {
                        enqueueToRouter(msg);
                    } catch (Exception e2) {
                        workerResources.notifyError(WorkerResourceProvider.Visibility.INTERNAL,
                                "Message ({}) was not possible to be re-enqueued to be persisted, please re-send manually", msg);
                    }
                }
            }
        }
    });
    private List<AbstractOutWorker<M>> beforeDoMsgFilters;
    private List<AbstractOutWorker<M>> afterDoMsgSuccessFilters;
    private List<AbstractOutWorker<M>> afterDoMsgFailureFilters;
    private double transactionsPerSecond;
    private RateLimiter rateLimiter;
    private FailDelayPolicy failDelayPolicy;

    protected AbstractOutWorker() {
        // for fast initialization
        this.vendorKpiHandler = new NoOpVendorKpiHandler();
        this.workerResources = new WorkerResourceProvider();
    }

    //For tests
    protected AbstractOutWorker(SendiumConfigurationProvider cp, Queue<M> routerQueue, String type, String instanceName) {
        if (type == null) {
            type = getType();
        }
        configurationProvider = cp;
        keepOnRunning = true;
        this.instanceName = instanceName;
        this.fullName = type + "." + instanceName;
        logger = LoggerFactory.getLogger("smsg.out." + fullName);
        configurationProvider.loadDefaultParams(getParamPrefix(), prms);
        threadCount = 1;
        maxRetries = 1;
        stats = new StatsKeeper();

        this.routerQueue = routerQueue;
        failDelayPolicy = new DefaultFailDelayPolicy(FailDelayPolicyAction.Action.SLEEP, FailDelayPolicyAction.Action.RE_ENQUEUE_ROUTER, 1000, 2000);
        healthCheckReport = new HealthCheckReport(getFullName(), HealthCheckCode.OK);
        this.sleeper = new Sleeper.NoopSleeper();

        this.vendorKpiHandler = new NoOpVendorKpiHandler();
        this.workerResources = new WorkerResourceProvider();
    }

    public AbstractOutWorker(SendiumConfigurationProvider configurationProvider, Queue routerQ) {
        this(configurationProvider, routerQ, null, "test");
    }

    public AbstractOutWorker(SendiumConfigurationProvider cp, String instName, Queue<M> routerQueue) {
        keepOnRunning = true;
        configurationProvider = cp;
        instanceName = instName;
        logger = LoggerFactory.getLogger("smsg.out." + getFullName());
        configurationProvider.loadDefaultParams(getParamPrefix(), prms);
        // <initStats>
        stats = new StatsKeeper();
        configurationProvider.loadDefaultParams(getParamPrefix(), stats.prms);
        stats.init(configurationProvider);
        // </initStats>
        this.sleeper = new Sleeper(getFullName());
        this.routerQueue = routerQueue;
        failDelayPolicy = createFailDelayPolicy();
        beforeDoMsgFilters = null;
        afterDoMsgFailureFilters = null;
        afterDoMsgSuccessFilters = null;
        maxRetries = this.configurationProvider.getIntPrpt(_maxRetries);
        threadCount = this.configurationProvider.getIntPrpt(_threadCount);
        debug = this.configurationProvider.getBlnPrpt(_debug);
        configRateLimiter();
        configPrintMsgs();
        configPause();

        this.vendorKpiHandler = new NoOpVendorKpiHandler();
        this.workerResources = new WorkerResourceProvider();
        healthCheckReport = new HealthCheckReport(getFullName(), HealthCheckCode.OK);
        workerResources.registerHealthCheckReporter(this);
    }

    public void setupInstance(SendiumConfigurationProvider cp, String instName, Queue<M> queue) {
        this.keepOnRunning = true;
        this.configurationProvider = cp;
        this.instanceName = instName;
        this.logger = LoggerFactory.getLogger("smsg.out." + getFullName());

        this.configurationProvider.loadDefaultParams(getParamPrefix(), prms);
        this.stats = new StatsKeeper();
        this.configurationProvider.loadDefaultParams(getParamPrefix(), stats.prms);
        this.stats.init(configurationProvider);

        this.sleeper = new Sleeper(getFullName());
        this.routerQueue = queue;
        this.failDelayPolicy = createFailDelayPolicy();

        this.maxRetries = this.configurationProvider.getIntPrpt(_maxRetries);
        this.threadCount = this.configurationProvider.getIntPrpt(_threadCount);
        this.debug = this.configurationProvider.getBlnPrpt(_debug);

        configRateLimiter();
        configPrintMsgs();
        configPause();

        this.healthCheckReport = new HealthCheckReport(getFullName(), HealthCheckCode.OK);
        this.workerResources.registerHealthCheckReporter(this);
    }

    public void init(WorkerResourceProvider resources, Tracker<M> messageTrackerInstance) {
        if (resources != null) {
            this.workerResources = resources;
            msgQ = checkSubscribeMessageQueue();
            registerPropertyChangeListener();
            this.messageTracker = messageTrackerInstance;
            this.messageTracker.init();
        }
    }

    public static void addPhaseToMessage(FilterLifecyclePhase phase, StandardMessage msg) {
        msg.field10 = phase;
    }

    public static void removePhaseFromMessage(StandardMessage msg) {
        msg.field10 = null;
    }

    public static FilterLifecyclePhase getAndRemovePhaseFromMessage(StandardMessage msg) {
        FilterLifecyclePhase phase = (FilterLifecyclePhase) msg.field10;
        removePhaseFromMessage(msg);
        return phase;
    }

    public void setVendorKpiHandler(VendorKpiHandler handler) {
        if (handler != null) {
            this.vendorKpiHandler = handler;
            configKPIs();
        }
    }

    public VendorKpiHandler getVendorKpiHandler() {
        return vendorKpiHandler;
    }

    public StatsKeeper getStats() {
        return stats;
    }

    public final String getInstanceName() {
        return instanceName;
    }

    public final String getFullName() {
        if (fullName == null) {
            fullName = Strings.nullToEmpty(getType()) + "." + Strings.nullToEmpty(getInstanceName());
        }
        return fullName;
    }

    public long getAlertMaxPendingQueue() {
        return alertMaxPendingQueue;
    }

    public long getAlertMaxRejectedQueue() {
        return alertMaxRejectedQueue;
    }

    public long getAlertMaxQueueSize() {
        return alertMaxQueueSize;
    }

    @Override
    public String toString() {
        return getFullName();
    }

    public abstract M doMessage(int pThreadIndex, M pMsg)
            throws IOException;

    public abstract String getType();

    public boolean isFilter() {
        return false;
    }

    public boolean isMessageTrackerEnabled() {
        return true;
    }

    // @Override
    public Thread start() {
        if (isFilter()) {
            //Filters do not have their own runtime.
            //The outgoing handler calls their doMessage
            return null;
        }
        keepOnRunning = true;
        int threadsNo = getThreadCount();
        var oldExecutor = executor;
        executor = Executors.newFixedThreadPool(threadsNo, Thread.ofPlatform().name(getFullName() + "-", 1).daemon(false).factory());

        for (int i = 0; i < threadsNo; i++) {
            try {
                executor.execute(new Worker(i));
            } catch (Exception ie) {
                handleException(ie);
            }
        }

        if (oldExecutor != null) {
            oldExecutor.shutdown();
            try {
                oldExecutor.awaitTermination(1, TimeUnit.MINUTES);
            } catch (Exception e) {
                logger.warn("error stopping old executor", e);
            }
        }

        return null;
    }

    public void onBatchUpdate(List<M> msgs) {
    }

    public boolean stop() {
        keepOnRunning = false;
        workerResources.stopExecutor(executor, logger, "internal");
        if (delayQDrainer.isAlive()) {
            try {
                //We could interrupt the delayQDrainer
                delayQDrainer.interrupt();
                delayQDrainer.join(Duration.ofMinutes(1).toMillis());
            } catch (Exception ie) {
                handleException(ie);
            }
        }
        if (stats.statsCnt > 0 || stats.statsPrd > 0) {
            logger.info(stats.getResetStats("shutdown"));
        }
        if (suspensionMonitorExecutor != null) {
            suspensionMonitorExecutor.shutdownNow();
            suspensionMonitorExecutor = null;
        }
        workerResources.unregisterHealthCheckReporter(this);
        unregisterPropertyChangeListener();
        Iterator<DelayedMessage<M>> iterator = delayQ.iterator();
        while (iterator.hasNext()) {
            //Remove all elements of the delayQ, in order not to loose messages that were scheduled for later on
            try {
                msgQ.enqueue((M) iterator.next().getMessage());
            } catch (InterruptedException e) {
                handleException(e);
            }
        }
        messageTracker.stop();
        return true;
    }

    protected void filterBeforeDoMessage(FilterLifecyclePhase phase, M msg) {
        // allows handling a message based on the filter phase just before the doMessage handling
        addPhaseToMessage(phase, msg);
    }

    public final void doFilter(FilterLifecyclePhase phase, M msg) throws IOException {
        if (!isFilter()) {
            throw new IllegalStateException("doFilter can only be called in filters.");
        }
        // <checkStats>
        String stat = stats.checkGetStats();
        if (stat != null) {
            logger.info(stat);
        }
        // </checkStats>
        if (printMsgs) {
            logger.info("{}", msg);
        }
        filterBeforeDoMessage(phase, msg);
        try {
            doMessage(-1, msg);
        } finally {
            removePhaseFromMessage(msg);
        }
    }

    protected void checkBeforeDoMessageFilters(M msg) throws IOException {
        applyFilters(beforeDoMsgFilters, msg, FilterLifecyclePhase.BEFORE_PROCESSING);
    }

    private void checkAfterDoMessageFailureFilters(M msg) throws IOException {
        applyFilters(afterDoMsgFailureFilters, msg, FilterLifecyclePhase.AFTER_FAILURE);
    }

    private void checkAfterDoMessageSuccessFilters(M msg) throws IOException {
        applyFilters(afterDoMsgSuccessFilters, msg, FilterLifecyclePhase.AFTER_SUCCESS);
    }

    private void applyFilters(List<AbstractOutWorker<M>> filters, M msg, FilterLifecyclePhase phase) throws IOException {
        if (filters != null) {
            //This implementation of the for loop uses an iterator so it preserves that the list of the filters
            //can be replaced by the OutgoingSmsHandler without interrupting the iteration in the ole list.
            for (var filter : filters) {
                try {
                    filter.doFilter(phase, msg);
                } catch (FilterException fe) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Filter({})->{}({}): {}",
                                fe.getFilter(),
                                fe.getStatusCode().name(),
                                fe.getMessage(),
                                fe.getMessageObj());
                    }
                    switch (fe.getStatusCode()) {
                        //When the filter either modifies some fields of the message or
                        //does not change any field, just continue the processing.
                        case MODIFIED:
                        case UNCHANGED:
                            continue;
                            //The drop,re-enqueue,retry, re-schedule and unknown policies are handled
                            //by the caller if the message has not already been consumed. Otherwise we
                            //cannot do anything apart from continue the filters processing.
                        case REENQUEUE:
                        case RETRY:
                        case RESCHEDULE:
                        case UNKNOWN:
                        case DROP:
                        default:
                            //We cannot re-act for a message that has already been consumed
                            if (phase == FilterLifecyclePhase.AFTER_SUCCESS) {
                                continue;
                            }
                            throw fe;
                    }
                }
            }
        }
    }

    public void enqueue(M pMsg) throws InterruptedException {
        if (isFilter()) {
            logger.debug("Filters do not have queues");
            return;
        }
        if (!this.keepOnRunning) {
            throw new IllegalStateException("Worker:" + getFullName() + " is stopped, cannot enqueue " + MessageTrace.identifiers(pMsg));
        }
        pMsg.outgateway = getFullName();
        if (MessageTrace.shouldLog(configurationProvider, MessageTrace.EVENT_ENQUEUED)) {
            logger.info("message.enqueued worker={} {}", getFullName(), MessageTrace.identifiers(pMsg));
        }
        msgQ.enqueue(pMsg);
    }

    public boolean enqueueNoExceptions(M pMsg) {
        try {
            enqueue(pMsg);
            return true;
        } catch (InterruptedException ie) {
            handleException(ie);
            return false;
        }
    }

    public SendiumConfigurationProvider getConfigurationProvider() {
        return configurationProvider;
    }

    public int getThreadCount() {
        return threadCount;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public Tracker<M> getMessageTracker() {
        return messageTracker;
    }

    public int updateSendStatusAndExtID(String smsid, M pMsg, String smscid) {
        pMsg.extrid = smscid;
        pMsg.field1 = smsid;
        return messageTracker.updateSendStatusAndExtID(smsid, pMsg, smscid);
    }

    public String getHashedMessageID(String messageId) {
        return messageTracker.getHashedMessageID(messageId);
    }

    public boolean isDebug() {
        return debug;
    }

    public boolean isPause() {
        return pause || suspendManually || (suspendAuto && suspensionPolicy == SuspensionPolicy.SUSPEND);
    }

    public boolean isManuallySuspended() {
        return suspendManually;
    }

    public boolean isAutoSuspended() {
        return suspendAuto;
    }

    public boolean acceptsMessages() {
        return keepOnRunning && !suspendManually && (!suspendAuto || !suspensionStopAcceptingMessages);
    }

    public void setPause(boolean pPause, boolean pSuspendManually, boolean pSuspendAuto) {
        pause = pPause;
        suspendManually = pSuspendManually;
        suspendAuto = pSuspendAuto;
        if (pause || suspendManually || suspendAuto) {
            final String action = suspendManually ? "MANUALLY SUSPENDED" : pause ? "PAUSED" : "AUTO SUSPENDED";
            logger.info("__{}__", action);
            if (!acceptsMessages()) {
                dequeueAllToRouter();
            } else {
                checkSubscribeMessageQueue();
            }
        } else {
            checkSubscribeMessageQueue();
            logger.info("__UNPAUSED__UNSUSPENDED__");
        }
    }

    public boolean isPrintMsgs() {
        return printMsgs;
    }

    public double getTransactionsPerSecond() {
        return transactionsPerSecond;
    }

    public void setTransactionsPerSecond(double tps) {
        transactionsPerSecond = tps;
    }

    protected void configRateLimiter() {
        double oldTps = getTransactionsPerSecond();
        double tps = Double.parseDouble(configurationProvider.getPrpt(_tps));

        try {
            if (tps > 0) {
                setTransactionsPerSecond(tps);
                logger.debug("Read tps: Setting tps<{}> ={}", oldTps, getTransactionsPerSecond());
            } else if (tps == 0) {
                setTransactionsPerSecond(Double.MAX_VALUE);
                logger.debug("Read tps: Setting tps<{}> ={}", oldTps, getTransactionsPerSecond());
            } else {
                //In this case the tps has a negative value so we just ignore it
                logger.debug("Read tps: Setting tps<{}> ={}", oldTps, getTransactionsPerSecond());
            }
            rateLimiter = RateLimiter.create(getTransactionsPerSecond());
        } catch (Exception ex) {
            handleException(ex);
            rateLimiter = RateLimiter.create(oldTps);
        }
    }

    protected void applyRateLimit() {
        if (rateLimiter != null) {
            rateLimiter.acquire();
        }
    }

    protected void setRateToRateLimiter(double rate) {
        if (rateLimiter != null) {
            rateLimiter.setRate(rate);
        }
    }

    public void dequeueAllToRouter() {
        if (isFilter()) {
            logger.debug("Filters do not have queues");
            return;
        }
        workerResources.geQueueProvider().unsubscribe(getFullName(), getQueueName());
    }

    public Queue<M> checkSubscribeMessageQueue() {
        if (isFilter()) {
            msgQ = null;
            return null;
        }
        var oldq = msgQ;
        msgQ = workerResources.geQueueProvider().subscribe(getFullName(), getQueueName(), configurationProvider.getBlnPrpt(_queuePriority));
        if (oldq != null && !oldq.isEmpty()) {
            oldq.drainTo(msgQ);
        }
        return getMsgQueue();
    }

    public String getQueueName() {
        String queueName = configurationProvider.getPrpt(_queueName);
        return Strings.isNullOrEmpty(queueName) ? instanceName : queueName;
    }

    /**
     * Enqueue messages directly to the queue of the router.
     *
     * @param msg The message to enqueue to the router
     */
    public final void enqueueToRouter(M msg) throws InterruptedException {
        routerQueue.enqueue(msg);
        if (MessageTrace.shouldLog(configurationProvider, MessageTrace.EVENT_ENQUEUED)) {
            logger.info("message.enqueued destination=router {}", MessageTrace.identifiers(msg));
        }
    }

    public void enqueueToRouterNoExceptions(M msg) {
        while (true) {
            try {
                enqueueToRouter(msg);
                return;
            } catch (Exception e) {
                logger.warn("exception re-enqueuing to router, will retry {}", MessageTrace.identifiers(msg), e);
                TimeUtils.sleep(100, TimeUnit.MILLISECONDS);
            }
        }
    }

    public final void enqueueDelayed(M msg, long delay) {
        if (isFilter()) {
            throw new UnsupportedOperationException("Filters do not have queues");
        }
        delayQ.add(new DelayedMessage<>(msg, delay));
        if (!delayQDrainer.isAlive()) {
            delayQDrainer.start();
        }
    }

    public Queue<M> getRouterQueue() {
        return routerQueue;
    }

    public void setRouterQueue(Queue q) {
        this.routerQueue = q;
    }

    public void setKeepOnRunning(boolean keepOnRunning) {
        this.keepOnRunning = keepOnRunning;
    }

    public boolean isKeepOnRunning() {
        return keepOnRunning;
    }

    public Queue<M> getMsgQueue() {
        if (isFilter()) {
            throw new UnsupportedOperationException("Filters do not have queues");
        }
        return msgQ;
    }

    public int getVendorFailRate() {
        if (vendorKpiHandler.isEnabled()) {
            return vendorKpiHandler.getFailureRate(workerResources);
        }
        return -1;
    }

    public int getVendorDeliveredRate() {
        if (vendorKpiHandler.isEnabled()) {
            return vendorKpiHandler.getDeliveredRate(workerResources);
        }
        return -1;
    }

    public int getVendorPendingRate() {
        if (vendorKpiHandler.isEnabled()) {
            return vendorKpiHandler.getPendingRate(workerResources);
        }
        return -1;
    }

    public String getVendorKPIsAcceptedFinalState(String dlr) {
        return vendorKpiHandler.getAcceptedFinalState(dlr);
    }

    public DelayQueue<DelayedMessage<M>> getDelayQueue() {
        return delayQ;
    }

    public List<AbstractOutWorker<M>> getBeforeDoMsgFilters() {
        if (beforeDoMsgFilters == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(beforeDoMsgFilters);
    }

    public void setBeforeDoMsgFilters(List<AbstractOutWorker<M>> beforeDoMsgFilters) {
        if (beforeDoMsgFilters != null) {
            logger.info("Setting beforeDoMsgFilters Filters: {}", beforeDoMsgFilters);
        }
        this.beforeDoMsgFilters = beforeDoMsgFilters;
    }

    public List<AbstractOutWorker<M>> getAfterDoMsgSuccessFilters() {
        if (afterDoMsgSuccessFilters == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(afterDoMsgSuccessFilters);
    }

    public void setAfterDoMsgSuccessFilters(List<AbstractOutWorker<M>> afterDoMsgSuccessFilters) {
        if (afterDoMsgSuccessFilters != null) {
            logger.info("Setting afterDoMsgSuccessFilters Filters: {}", afterDoMsgSuccessFilters);
        }
        this.afterDoMsgSuccessFilters = afterDoMsgSuccessFilters;
    }

    public List<AbstractOutWorker<M>> getAfterDoMsgFailureFilters() {
        if (afterDoMsgFailureFilters == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(afterDoMsgFailureFilters);
    }

    public void setAfterDoMsgFailureFilters(List<AbstractOutWorker<M>> afterDoMsgFailureFilters) {
        if (afterDoMsgFailureFilters != null) {
            logger.info("Setting afterDoMsgFailureFilters Filters: {}", afterDoMsgFailureFilters);
        }
        this.afterDoMsgFailureFilters = afterDoMsgFailureFilters;
    }

    public String getBeforeDoMsgFiltersList() {
        return configurationProvider.getPrpt(_filtersBeforeDoMsg);
    }

    public String getAfterDoMsgSuccessFiltersList() {
        return configurationProvider.getPrpt(_filtersAfterDoMsgSuccess);
    }

    public String getAfterDoMsgFailureFiltersList() {
        return configurationProvider.getPrpt(_filtersAfterDoMsgFailure);
    }

    /**
     * Check whether charmapping should be performed for the given message
     * and return the message's original body, which will be used to reset it if submit fails
     *
     * @param msg the message to check if charmap should be performed. If it should, then the result of charMap should
     *            be saved in message's body and original body should be returned
     * @return the message's original body, before any charmapping occurs
     */
    protected String doCharMap(M msg) {
        if (isFilter() || (msg.type != StandardMessage.MSG_TEXT && msg.type != StandardMessage.MSG_FLASH)) {
            return msg.body;
        }

        String original = msg.body;
        msg.body = charMap(msg.body);
        return original;
    }

    protected String charMap(String in) {
        String charmapName = null;
        if (configurationProvider.getBlnPrpt(_charmapperEnabled)) {
            charmapName = configurationProvider.getPrpt(_charmapperName);
        }
        return workerResources.charMappingOut(charmapName, in);
    }

    public String getParamPrefix() {
        return "outSms.instance." + getInstanceName();
    }

    public void prependInstanceName(String[][] params) {
        for (int i = 0; i < params.length; i++) {
            params[i][0] = getParamPrefix() + "." + params[i][0];
        }
    }

    /**
     * Called when a property was modified. Implementations should check if the key applies to one of it's
     * properties, make the change and return true, otherwise they must pass the call to their parent.
     * <br />
     * <b>If property change is not consumed, it must call the parent implementation</b>
     *
     * @param key      The name of the property changing
     * @param newValue The new value of the property
     * @param oldValue The old value of the property
     * @return true if property was consumed by the implementation, false otherwise
     */
    protected boolean myPropertyChange(String key, String newValue, String oldValue) {
        return false;
    }

    private void myPropertyChange(PropertyChangeEvent evt) {
        logger.trace("myPropertyChange({}={}<{}>)", evt.getKey(), evt.getNewValue(), evt.getOldValue());
        String key = evt.getKey();
        String newValue = evt.getNewValue();
        String oldValue = evt.getOldValue();
        //Ensure that abstract properties are always changed and are not dependent on implementations to properly propagate.
        //This is to make sure that core functionality works properly for all implementations
        if (localMyPropertyChange(key, newValue, oldValue) ||
                myPropertyChange(key, newValue, oldValue)) {
            return;
        }
        stats.doPropertyChange(configurationProvider, evt);
    }

    private boolean localMyPropertyChange(String key, String newValue, String oldValue) {
        messageTracker.configure(key, newValue, oldValue);
        if (key.equals(_debug[0])) {
            debug = configurationProvider.getBlnPrpt(_debug);
        } else if (key.equals(_printMsgs[0])) {
            configPrintMsgs();
        } else if (key.equals(_pause[0]) || key.equals(_suspendManually[0]) || key.equals(_suspensionPolicy[0])) {
            configPause();
        } else if (key.equals(_suspensionDisableAfter[0]) || key.equals(_suspensionStopMessagesAfter[0])) {
            setupAutoSuspensionMonitor();
        } else if (key.equals(_maxRetries[0])) {
            maxRetries = configurationProvider.getIntPrpt(_maxRetries);
        } else if (key.equals(_tps[0])) {
            configRateLimiter();
        } else if (key.equals(_failActionWorkerSleep[0])) {
            failDelayPolicy = createFailDelayPolicy();
        } else if (key.equals(_failActionRouterSleep[0])) {
            failDelayPolicy = createFailDelayPolicy();
        } else if (key.equals(_failActionWorkerType[0])) {
            failDelayPolicy = createFailDelayPolicy();
        }  else if (key.equals(_kpiEnabled[0]) || key.equals(_kpiPeriod[0]) || key.equals(_kpiVolume[0]) || key.equals(_kpiFailStatuses[0])) {
            configKPIs();
        } else if (key.equals(_failActionRouterType[0])) {
            failDelayPolicy = createFailDelayPolicy();
        } else if (key.equals(_alertMaxPending[0]) || key.equals(_alertMaxRejected[0]) || key.equals(_alertMaxQueueSize[0])) {
            configAlertQueue();
        } else {
            return false;
        }
        return true;
    }

    private void configPrintMsgs() {
        printMsgs = configurationProvider.getBlnPrpt(_printMsgs);
    }

    protected void configAlertQueue() {
        alertMaxPendingQueue = configurationProvider.getLongPrpt(_alertMaxPending);
        alertMaxRejectedQueue = configurationProvider.getLongPrpt(_alertMaxRejected);
        alertMaxQueueSize = configurationProvider.getLongPrpt(_alertMaxQueueSize);
        logger.info("Set maxPending: {}, maxRejected: {}, maxQueueSize: {}", alertMaxPendingQueue, alertMaxRejectedQueue, alertMaxQueueSize);
    }

    public void configKPIs() {
        if (vendorKpiHandler != null) {
            vendorKpiHandler.configure(configurationProvider, this);
            this.kpiEnabled = vendorKpiHandler.isEnabled();
        }
    }

    public void configPause() {
        boolean oldPause = pause;
        boolean newPause = configurationProvider.getBlnPrpt(_pause);
        boolean oldSuspend = suspendManually;
        boolean newSuspend = configurationProvider.getBlnPrpt(_suspendManually);

        var oldPolicy = suspensionPolicy;
        suspensionPolicy = SuspensionPolicy.of(configurationProvider.getPrpt(_suspensionPolicy));

        if (oldPolicy != null && oldPolicy != suspensionPolicy) {
            logger.warn("Changed suspension policy to: {}", suspensionPolicy);
        }

        if (oldPause != newPause || oldSuspend != newSuspend || (oldPolicy != null && oldPolicy != suspensionPolicy)) {
            setPause(newPause, newSuspend, suspendAuto);
        }
    }

    public void initiateAutoSuspension() {
        suspendAuto = true;
        suspensionStart = System.currentTimeMillis();
        setupAutoSuspensionMonitor();
    }

    protected void setupAutoSuspensionMonitor() {
        if (suspensionMonitorExecutor != null) {
            suspensionMonitorExecutor.shutdownNow();
            suspensionMonitorExecutor = null;
        }
        if (!suspendAuto) {
            return;
        }
        int suspensionDisableMs = configurationProvider.getIntPrpt(_suspensionDisableAfter);
        int suspensionStopMessagesMs = configurationProvider.getIntPrpt(_suspensionStopMessagesAfter);
        if (suspensionDisableMs == 0) {
            suspensionActionDisable();
            // worker was just disabled, nothing further to do
            return;
        } else if (suspensionStopMessagesMs == 0) {
            suspensionActionStopMessages();
        }
        if (suspensionDisableMs > 0 || suspensionStopMessagesMs > 0) {
            suspensionMonitorExecutor = Executors.newScheduledThreadPool(1, Thread.ofVirtual().name("suspensionMonitorExecutor-", 1).factory());
            if (suspensionDisableMs > 0) {
                suspensionMonitorExecutor.schedule(this::suspensionActionDisable, suspensionDisableMs, TimeUnit.MILLISECONDS);
            }
            if (suspensionStopMessagesMs > 0) {
                suspensionMonitorExecutor.schedule(this::suspensionActionStopMessages, suspensionStopMessagesMs, TimeUnit.MILLISECONDS);
            }
        }
        setPause(pause, suspendManually, suspendAuto);
    }

    protected void endAutoSuspension() {
        suspendAuto = false;
        suspensionStart = 0;
        if (suspensionMonitorExecutor != null) {
            suspensionMonitorExecutor.shutdownNow();
            suspensionMonitorExecutor = null;
        }
        setPause(pause, suspendManually, suspendAuto);
    }

    protected void suspensionActionDisable() {
        if (!suspendAuto) {
            logger.warn("Action DISABLE called, but not auto-suspended!");
            return;
        }
        int period = configurationProvider.getIntPrpt(_suspensionDisableAfter);
        workerResources.notifyError(WorkerResourceProvider.Visibility.EXTERNAL, "Worker:{} DISABLED after suspension period: {}ms", getFullName(), period);
        if (!workerResources.checkIfWorkerExists(getFullName())) {
            logger.warn("disabled during start, so manually stopping self");
            try {
                this.stop();
            } catch (Exception e) {
                logger.warn("error stopping self", e);
            }
            this.keepOnRunning = false;
        }
        configurationProvider.storeProperties(Map.of(getParamPrefix() + ".enable", "false"));
    }

    protected void suspensionActionStopMessages() {
        if (!suspendAuto) {
            logger.warn("Action STOP MESSAGES called, but not auto-suspended!");
            return;
        }
        int period = configurationProvider.getIntPrpt(_suspensionStopMessagesAfter);
        workerResources.notifyError(WorkerResourceProvider.Visibility.EXTERNAL,
                "Worker: {} STOPPING receiving messages from router (and rerouting any messages in current queue) after suspension period: {}ms",
                getFullName(), period);
        suspensionStopAcceptingMessages = true;
        // drain our queue to router
        dequeueAllToRouter();
        if (suspensionMonitorExecutor != null && suspensionMonitorExecutor instanceof ScheduledThreadPoolExecutor tpe && tpe.getQueue().isEmpty()) {
            suspensionMonitorExecutor.shutdownNow();
            suspensionMonitorExecutor = null;
        }
    }

    private void registerPropertyChangeListener() {
        propertyChangeListener = this::myPropertyChange;
        // register the property change hook
        configurationProvider.addPropertyChangeListener(propertyChangeListener);
    }

    private void unregisterPropertyChangeListener() {
        configurationProvider.removePropertyChangeListener(propertyChangeListener);
    }

    /**
     * This method decides whether the current worker implementation handles messages synchronously
     * in doMessage, or if the response is asynchronous, thus a null response does not necessarily mean
     * that the message handling was successful
     *
     * @return whether the message handling is synchronous
     */
    public boolean isMessageHandlingSynchronous() {
        return true;
    }

    public void onMessageTemporaryFailed(M m) {
        onMessageFailed(m, true);
    }

    public void onMessageFailed(M m) {
        onMessageFailed(m, false);
    }

    protected void onMessageFailed(M m, boolean shouldAttemptWorkerRetry) {
        int internalTries = failedMsgCounter.getOrDefault(m.msgId, 0);
        internalTries++;
        boolean enqueueInstead = false;
        M msg = m;
        try {
            checkAfterDoMessageFailureFilters(m);
        } catch (FilterException fe) {
            switch (fe.getStatusCode()) {
                case DROP:
                    //We just drop the message
                    m = null;
                    break;
                case RETRY:
                    //We need to assign the filtered message object into the m variable.
                    m = (M) fe.getMessageObj();
                    internalTries = getMaxRetries() + 1; //Will cause message to go out of the worker
                    break;
                case RESCHEDULE:
                    //At the moment the reschedule policy is not implemented and its
                    //status fallback status is the re-enqueue status. We just inform
                    //the user and let the re-enqueue to take care of it.
                    logger.warn("The RESCHEDULE filter is not yet implemented. Fallback to REENQUEUE.");
                    // fall through
                case UNKNOWN:
                case REENQUEUE:
                default:
                    //We need to assign the filtered message object into the m variable.
                    m = (M) fe.getMessageObj();
                    internalTries = getMaxRetries() + 1; //Will cause message to go out of the worker
                    enqueueInstead = true;
                    break;
            }
        } catch (Exception e) {
            logger.error("Exception caught in worker", e);
        }

        if (!shouldAttemptWorkerRetry || m != msg || (internalTries >= getMaxRetries() && getMaxRetries() != 0)) {
            if (m != null) {
                if (MessageTrace.shouldLog(configurationProvider, MessageTrace.EVENT_DELIVERY_FAILED)) {
                    logger.info("message.delivery.failed mode=async tries={} {}", internalTries, MessageTrace.identifiers(msg));
                }
                handleMessageFailInWorker("async", m, enqueueInstead);
            }
            return;
        }

        failedMsgCounter.put(m.msgId, internalTries);

        if (MessageTrace.shouldLog(configurationProvider, MessageTrace.EVENT_DELIVERY_RETRY)) {
            logger.info("message.delivery.retry mode=async tries={} {}", internalTries, MessageTrace.identifiers(msg));
        }

        m = doFailDelayWorkerRetryPolicyAction(m, internalTries);

        if (m != null) {
            enqueueNoExceptions(m);
        }
    }

    public void onMessageSuccess(M msg) throws IOException {
        try {
            failedMsgCounter.remove(msg.msgId);
            checkAfterDoMessageSuccessFilters(msg);
        } catch (FilterException fe) {
            //after message success, we do not expect to handle any filter exception
            logger.warn("unexpected filter exception onMessageSuccess {}", MessageTrace.identifiers(msg), fe);
        }
    }

    protected void handleMessageFailInWorker(String id, M m, boolean enqueueInstead) {
        //No more trials in the worker
        if (m != null) {
            //The message was not delivered by the worker, see what to do with it
            try {
                logger.info("{}: requeueing: {}", id, m);
                if (enqueueInstead) {
                    if (routerQueue == null) {
                        logger.warn("Worker {} does not have a reference to router", getFullName());
                        msgQ.enqueue(m);
                    } else {
                        enqueueToRouter(m);
                    }
                } else {
                    if (++m.rtxCnt < 1) {
                        m.rtxCnt = 1;
                    }
                    doFailDelayWorkerEndRetryPolicyAction(m);
                }
            } catch (InterruptedException ie) {
                handleException(ie);
            }
            failedMsgCounter.remove(m.msgId);
        }
    }

    protected M doFailDelayWorkerRetryPolicyAction(M m, int tries) {
        //Use a policy on what to do, allowing other workers to override it and make it better fit their needs
        FailDelayPolicyAction action = failDelayPolicy.getActionForMessage(m, FailDelayPolicy.Stage.WORKER_RETRY, tries);
        if (action.sleepBeforeActionMs > 0) {
            sleeper.sleep(action.sleepBeforeActionMs, TimeUnit.MILLISECONDS);
        }
        switch (action.action) {
            case RE_ENQUEUE_WORKER:
                try {
                    enqueue(m);
                    m = null;
                } catch (InterruptedException e) {
                    handleException(e);
                }
                break;
            case RE_ENQUEUE_ROUTER:
                try {
                    if (++m.rtxCnt < 1) {
                        //Increment re-try count to make sure that we stop at some point
                        m.rtxCnt = 1;
                    }
                    enqueueToRouter(m);
                    failedMsgCounter.remove(m.msgId);
                    m = null;
                } catch (InterruptedException e) {
                    handleException(e);
                }
                break;
            case RE_ENQUEUE_WORKER_DELAYED:
                enqueueDelayed(m, configurationProvider.getLongPrpt(_failActionDelayedDelay));
                m = null;
                break;
            case CUSTOM:
                if (!customFailAction(m, FailDelayPolicy.Stage.WORKER_RETRY, tries)) {
                    failedMsgCounter.remove(m.msgId);
                    m = null;
                }
                break;
            case SLEEP:
            default:
                //Nothing to do
        }
        if (action.sleepAfterActionMs > 0) {
            sleeper.sleep(action.sleepAfterActionMs, TimeUnit.MILLISECONDS);
        }

        return m;
    }

    protected void doFailDelayWorkerEndRetryPolicyAction(M m) throws InterruptedException {
        FailDelayPolicyAction action = failDelayPolicy.getActionForMessage(m, FailDelayPolicy.Stage.WORKER_END_RETRY, m.rtxCnt);
        if (action.sleepBeforeActionMs > 0) {
            sleeper.sleep(action.sleepBeforeActionMs, TimeUnit.MILLISECONDS);
        }
        switch (action.action) {
            case CUSTOM:
                if (customFailAction(m, FailDelayPolicy.Stage.WORKER_END_RETRY, m.rtxCnt)) {
                    //Default action is to enqueue to router, otherwise it will be just dropped
                    if (routerQueue == null) {
                        logger.warn("Worker {} does not have a reference to router", getFullName());
                        msgQ.enqueue(m);
                    } else {
                        enqueueToRouter(m);
                    }
                }
                break;
            case RE_ENQUEUE_WORKER_DELAYED:
            case RE_ENQUEUE_WORKER:
            case SLEEP:
            default:
                //Avoid re-enqueuing to worker in order not to break configuration
                logger.info("Overriding action {} and re-enqueuing to router instead", action.action);
                //fall through
            case RE_ENQUEUE_ROUTER:
                if (routerQueue == null) {
                    logger.warn("Worker {} does not have a reference to router", getFullName());
                    msgQ.enqueue(m);
                } else {
                    enqueueToRouter(m);
                }
                break;
        }
        if (action.sleepAfterActionMs > 0) {
            sleeper.sleep(action.sleepAfterActionMs, TimeUnit.MILLISECONDS);
        }
    }

    public HealthCheckReport getHealthCheckReport() {
        return healthCheckReport;
    }

    public HealthCheckReport checkAndGetHealthCheckReport() {
        healthCheckReport = new HealthCheckReport(healthCheckReport.getId(), healthCheckReport.getStatus());
        return healthCheckReport;
    }

    public void handleException(Throwable t) {
        if (t != null) {
            logger.warn(t.getMessage(), t);
        } else {
            logger.warn("handleException was called with a null Throwable.");
        }
    }

    public DataSource getDataSource() {
        return workerResources.getDataSource();
    }

    public WorkerResourceProvider getWorkerResources() {
        return workerResources;
    }

    protected FailDelayPolicy createFailDelayPolicy() {
        long sleepBeforeWorker = configurationProvider.getLongPrpt(_failActionWorkerSleep[0],
                Long.parseLong(_failActionWorkerSleep[1]));
        long sleepBeforeRouter = configurationProvider.getLongPrpt(_failActionRouterSleep[0],
                Long.parseLong(_failActionRouterSleep[1]));

        FailDelayPolicyAction.Action workerAction = FailDelayPolicyAction.Action.SLEEP;
        FailDelayPolicyAction.Action routerAction = FailDelayPolicyAction.Action.RE_ENQUEUE_ROUTER;

        String workerActStr = configurationProvider.getPrpt(_failActionWorkerType);
        String routerActStr = configurationProvider.getPrpt(_failActionRouterType);
        for (FailDelayPolicyAction.Action action : FailDelayPolicyAction.Action.values()) {
            if (action.toString().equalsIgnoreCase(workerActStr)) {
                workerAction = action;
            }
            if (action.toString().equalsIgnoreCase(routerActStr)) {
                routerAction = action;
            }
        }
        return new DefaultFailDelayPolicy(workerAction, routerAction, sleepBeforeWorker, sleepBeforeRouter);
    }

    public final FailDelayPolicy getFailDelayPolicy() {
        return failDelayPolicy;
    }

    public final void setFailDelayPolicy(FailDelayPolicy policy) {
        if (policy == null) {
            logger.warn("Cannot set FailDelayPolicy to null, ignoring call");
            return;
        }
        this.failDelayPolicy = policy;
    }

    /**
     * The custom action of the worker during a message failure.
     * <br />
     * <b>WARNING</b> Using the custom action you must make sure that the message does not end in an infinite loop!
     *
     * @return true to continue with normal operations of the message, false to drop the message
     */
    protected boolean customFailAction(M msg, FailDelayPolicy.Stage stage, int trial) {
        //Do nothing by default, return the message
        logger.warn("CustomFailAction is no-op in {}", getFullName());
        return true;
    }

    public enum FilterLifecyclePhase {
        DURING_ROUTING, BEFORE_PROCESSING, AFTER_SUCCESS, AFTER_FAILURE, BEFORE_INSERT;
    }

    public enum SuspensionPolicy {
        SUSPEND, RETRY_ROUTER, FAIL;

        public static SuspensionPolicy of(String prop) {
            return Arrays.stream(SuspensionPolicy.values()).filter(sp -> sp.name().equalsIgnoreCase(prop)).findFirst()
                    .orElse(SuspensionPolicy.SUSPEND);
        }
    }

    public static class DelayedMessage<M extends StandardMessage> implements Delayed {
        private final long enter;
        private final long delayTime;
        private final M msg;

        private DelayedMessage(M msg, long delayTime) {
            this.enter = System.currentTimeMillis();
            this.delayTime = delayTime;
            this.msg = msg;
        }

        public M getMessage() {
            return msg;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert((enter + delayTime) - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed o) {
            return o instanceof DelayedMessage<?> dm ? msg.compareTo(((DelayedMessage<M>) dm).getMessage()) : -1;
        }
    }

    public class Worker implements Runnable {
        private final int id;
        private M msg;

        public Worker(int id) {
            this.id = id;
            msg = null;
        }

        @Override
        public void run() {
            int failureCount = 0;
            while (keepOnRunning) {
                try {
                    if (isPause()) {
                        healthCheckReport = HealthCheckReport.checkAndGet(healthCheckReport, HealthCheckCode.PAUSED);
                        logger.trace("worker {} is paused, sleeping", getFullName());
                        sleeper.sleep(configurationProvider.getLongPrpt(_pauseSleepMs), TimeUnit.MILLISECONDS);
                        continue;
                    }
                    applyRateLimit();
                    if (msg == null) {
                        // wait up to 200ms for the next Message
                        msg = msgQ.dequeue(200);
                    }
                    // if no Message returned retry
                    if (msg == null) {
                        continue;
                    }
                    if (!keepOnRunning) {
                        logger.info("enqueued message msg:{} back to queue since worker stopping, and keepOnRunning:{}", msg.msgId, keepOnRunning);
                        msgQ.enqueue(msg);
                        return;
                    }
                    // <checkStats>
                    String stat = stats.checkGetStats();
                    if (stat != null) {
                        logger.info(stat);
                    }
                    try {
                        Arc.container().requestContext().activate();
                        handleMessage();
                    } finally {
                        Arc.container().requestContext().terminate();
                    }
                    msg = null;
                    failureCount = 0;
                } catch (Exception e) {
                    if (e instanceof InterruptedException || !keepOnRunning) {
                        if (msg != null) {
                            enqueueNoExceptions(msg);
                            msg = null;
                        }
                        continue;
                    }
                    failureCount++;
                    if ((failureCount - 5) % 60 == 0) {
                        workerResources.notifyError(WorkerResourceProvider.Visibility.INTERNAL,
                                "WARNING: CAUGHT EXCEPTION. FAILURE COUNT EXCEEDED THRESHOLD : <br>" + e.getMessage());
                    }
                    logger.warn("caught exception", e);
                    sleeper.sleep(Math.min(failureCount, 60), TimeUnit.SECONDS);
                }
            }
        }

        public void handleMessage() {
            M m;
            int internalTries = 0;
            boolean enqueueInstead = false;

            do {
                internalTries++;
                m = msg;
                String originalMessageBody = doCharMap(m);
                try {
                    if (printMsgs) {
                        logger.info("{}", msg);
                    }
                    //Pass the message from the filters chain
                    checkBeforeDoMessageFilters(msg);

                    //Handle the message
                    m = doMessage(id, msg);

                    //If the message has been consumed, call the post consumption filters
                    if (m == null && isMessageHandlingSynchronous()) {
                        failedMsgCounter.remove(msg.msgId);
                        checkAfterDoMessageSuccessFilters(msg);
                    } else if (m != null) { //Otherwise, call the post failed to be consumed filters
                        checkAfterDoMessageFailureFilters(m);
                    }
                } catch (FilterException fe) {
                    switch (fe.getStatusCode()) {
                        case DROP:
                            //We just drop the message
                            m = null;
                            break;
                        case RETRY:
                            //We need to assign the filtered message object into the m variable.
                            m = (M) fe.getMessageObj();
                            internalTries = getMaxRetries() + 1; //Will cause message to go out of the worker
                            break;
                        case RESCHEDULE:
                            //At the moment the reschedule policy is not implemented and its
                            //status fallback status is the re-enqueue status. We just inform
                            //the user and let the re-enqueue to take care of it.
                            logger.warn("The RESCHEDULE filter is not yet implemented. Fallback to REENQUEUE.");
                            // fall through
                        case UNKNOWN:
                        case REENQUEUE:
                        default:
                            //We need to assign the filtered message object into the m variable.
                            m = (M) fe.getMessageObj();
                            internalTries = getMaxRetries() + 1; //Will cause message to go out of the worker
                            enqueueInstead = true;
                            break;
                    }
                } catch (Exception e) {
                    logger.error("Exception caught in worker", e);
                } finally {
                    if (m != null) {
                        m.body = originalMessageBody;
                    }
                }
                if (m == null || m != msg || (internalTries >= getMaxRetries() && getMaxRetries() != 0)) {
                    if (m != null && !enqueueInstead) {
                        if (MessageTrace.shouldLog(configurationProvider, MessageTrace.EVENT_DELIVERY_FAILED)) {
                            logger.info("message.delivery.failed workerThread={} tries={} {}", id, internalTries,
                                    MessageTrace.identifiers(msg));
                        }
                    }
                    break; //Stop trying in the worker
                }

                //We need to try the message again
                int tries = internalTries;
                Integer retries = failedMsgCounter.putIfAbsent(m.msgId, internalTries);
                if (retries != null) {
                    //Old value was set, so we check based on this trial + old trials
                    tries = retries + 1;
                    failedMsgCounter.put(m.msgId, tries);
                } //else we use the current tries

                if (MessageTrace.shouldLog(configurationProvider, MessageTrace.EVENT_DELIVERY_RETRY)) {
                    logger.info("message.delivery.retry workerThread={} tries={} {}", id, tries, MessageTrace.identifiers(msg));
                }

                if (tries >= getMaxRetries() && getMaxRetries() != 0) {
                    //Current retries + previous ones enforce us to break the loop
                    break;
                }

                m = doFailDelayWorkerRetryPolicyAction(m, tries);

                if (m == null) {
                    break; //Nothing more to process
                }
            } while (true);

            handleMessageFailInWorker(String.valueOf(id), m, enqueueInstead);
        }

        public Worker setMessage(M msgPrm) {
            msg = msgPrm;
            return this;
        }
    }
}
