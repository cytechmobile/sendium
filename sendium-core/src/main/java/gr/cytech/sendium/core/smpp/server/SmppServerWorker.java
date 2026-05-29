package gr.cytech.sendium.core.smpp.server;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.commons.util.ByteArrayUtil;
import com.cloudhopper.commons.util.HexUtil;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppServer;
import com.cloudhopper.smpp.SmppServerConfiguration;
import com.cloudhopper.smpp.impl.DefaultSmppServer;
import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.Pdu;
import com.cloudhopper.smpp.ssl.SslConfiguration;
import com.cloudhopper.smpp.tlv.Tlv;
import com.cloudhopper.smpp.type.Address;
import com.cloudhopper.smpp.type.SmppInvalidArgumentException;
import com.cloudhopper.smpp.util.DeliveryReceipt;
import com.google.common.base.Strings;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import gr.cytech.sendium.conf.SendiumConfigurationProvider;
import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.queue.Queue;
import gr.cytech.sendium.core.smpp.server.tasks.InTask;
import gr.cytech.sendium.core.smpp.server.tasks.InactivityTimeTask;
import gr.cytech.sendium.core.smpp.server.tasks.OutTask;
import gr.cytech.sendium.core.smpp.server.tasks.PrintStatisticsTask;
import gr.cytech.sendium.core.smpp.util.SmppServerUtil;
import gr.cytech.sendium.core.worker.FailDelayPolicy;
import gr.cytech.sendium.core.worker.FailDelayPolicyAction;
import gr.cytech.sendium.core.worker.WorkerType;
import gr.cytech.sendium.external.filter.FilterException;
import gr.cytech.sendium.external.filter.FilterStatusCodes;
import gr.cytech.sendium.external.filter.InMessageFiltering;
import gr.cytech.sendium.util.MessageTrace;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import jakarta.enterprise.context.Dependent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Dependent
@WorkerType(SmppServerWorker.TYPE_SMPP_SERVER)
public class SmppServerWorker<M extends StandardMessage> extends AbstractOutWorker<M> implements InMessageFiltering {
    public static final String TYPE_SMPP_SERVER = "smppserver";
    public static final Logger statsLogger = LoggerFactory.getLogger(SmppServerWorker.class.getCanonicalName() + ".statistics");
    public boolean flagReverseDlrSrcDst;
    public volatile ScheduledFuture<?> inactivityTimeFuture;
    public InTask<M> inExecutorRunnable;
    public SmppServerMessageStore<M> messageStore;
    public SmppServerBindHandler<M> bindHandler;

    public final String[] _sleepWindow = {"conf.sleepWindow", "1000"};
    public final String[] _srvMaxInactivityTime = {"srv.maxInactivityTime", "60"};
    public final String[] _srvMonitorThreads = {"srv.monitor.threads", "1"};
    public final String[] _srvOutThreads = {"srv.out.threads", "10"};
    public final String[] _logBytes = {"log.bytes", "false"};
    public final String[] _logPdus = {"log.pdus", "false"};

    protected final String[] _srvEnabled = {"srv.enabled", "true"};
    protected final String[] _srvPort = {"srv.port", "0"};
    protected final String[] _srvHost = {"srv.host", "localhost"};
    protected final String[] _srvSystemId = {"srv.systemId", "Sendium"};
    protected final String[] _srvThreads = {"srv.threads", "1"};
    protected final String[] _srvWorkerThreads = {"srv.worker.threads", "10"};
    protected final String[] _srvJmxEnabled = {"srv.jmx.enabled", "false"};
    protected final String[] _srvJmxDomain = {"srv.jmx.domain", "gr.cytech.sendium"};
    protected final String[] _srvMaxConnections = {"srv.maxConnections", "1000"};
    protected final String[] _srvBindTimeout = {"srv.bindTimeout", "5000"};
    protected final String[] _srvStatsPrintPeriod = {"srv.printStatsPeriod", "300"};
    protected final String[] _srvRatePrintPeriod = {"srv.printRatePeriod", "60"};
    protected final String[] _srvRatePrintCount = {"srv.printRateCount", "0"};
    protected final String[] _srvMaxConnectionsPerIp = {"srv.maxConnectionsPerIP", "0"};
    protected final String[] _srvTlsEnabled = {"srv.tls.enabled", "false"};
    protected final String[] _srvTlsPort = {"srv.tls.port", "0"};
    protected final String[] _srvTlsHost = {"srv.tls.host", "localhost"};
    protected final String[] _srvTlsKeystorePath = {"srv.tls.keystore.path", ""};
    protected final String[] _srvTlsKeystoreAlias = {"srv.tls.keystore.alias", ""};
    protected final String[] _srvTlsKeystorePass = {"srv.tls.keystore.password", ""};
    protected final String[] _srvTlsReload = {"srv.tls.reload", ""};
    protected final String[] _srvProxyEnabled = {"srv.proxy.enabled", "false"};
    protected final String[] _srvProxyPort = {"srv.proxy.port", "2777"};
    protected final String[] _ptrnVreceiver = {"ptrn.valid.receiver", "[+]?[0-9]{10,20}"};
    protected final String[] _forwardDlrs = {"forward.dlrs", "true"};
    protected final String[] _charsetGsm = {"charset.gsm", CharsetUtil.NAME_GSM};
    protected final String[] _charsetLatin1 = {"charset.latin1", CharsetUtil.NAME_ISO_8859_1};
    protected final String[] _charsetUcs2 = {"charset.ucs2", CharsetUtil.NAME_UCS_2};
    protected final String[] _ccat8bit = {"ccat.8bit", "true"};
    protected final String[] _defaultMaxRate = {"conf.maxRate.default", "0"};
    protected final String[] _defaultMaxConnectionsPerUser = {"conf.maxConnectionsPerUser.default", "0"};
    protected final String[] _defaultWindowMonitorInterval = {"conf.windowMonitorInterval.default", "15000"};
    protected final String[] _defaultResponseTimeout = {"conf.responseTout.default", "30000"};
    protected final String[] _defaultMaxPending = {"conf.maxPending.default", "1000"};
    protected final String[] _defaultWriteTimeout = {"conf.writeTimeout.default", "30000"};
    protected final String[] _logPdusExclude = {"log.pdus.exclude", SmppConstants.CMD_ID_ENQUIRE_LINK + "," + SmppConstants.CMD_ID_ENQUIRE_LINK_RESP};
    protected final String[] _filtersBeforeInsertMessage = {"filters.beforeInsertMessage", ""};
    protected final String[] _flagReverseDlrSrcDst = {"flag.reverseDlrSrcDst", "true"};
    protected final String[] _timeoutMillis = {"reassembling.timeoutMillis", "30000"};

    protected SmppAuthenticationProvider authProvider;
    protected SubmitSmProcessor<M> submitProcessor;
    protected volatile LinkedBlockingQueue<InEvent<M>> inEventQueue;
    protected SmppServer server;
    protected SmppServer tlsServer;
    protected SmppServer proxyServer;
    protected volatile ScheduledFuture<?> serverStatsScheduledFuture;
    protected ScheduledThreadPoolExecutor statsManager;
    protected ScheduledThreadPoolExecutor monitorExecutor;
    protected ScheduledExecutorService inExecutor;
    protected List<AbstractOutWorker> beforeInsertMessageFilters;
    protected ThreadPoolExecutor outExecutor;
    protected AtomicInteger msgRefNumGenerator;
    protected AtomicInteger msgPartId;
    protected SmppServerSessionCounters totalCounters;
    protected MessagePartsHandler<M> messagePartsHandler;
    protected boolean isFastUnsafeStop = false;

    public SmppServerWorker() {
        this.authProvider = new BasicSmppAuthenticationProvider(this);
        this.submitProcessor = new BasicSubmitSmProcessor(this);
    }

    public SmppServerWorker(
            SendiumConfigurationProvider configurationProvider,
            String instName,
            Queue routerQ
    ) {
        super(configurationProvider, instName, routerQ);
        setUp();
    }

    public void setupInstance(SendiumConfigurationProvider cp, String instName, Queue<M> routerQueue) {
        super.setupInstance(cp, instName, routerQueue);
        setUp();
    }

    protected void setUp() {
        String[][] allPrms = new String[][]{
                _logPdus, _sleepWindow, _srvMaxInactivityTime, _srvMonitorThreads, _srvOutThreads,
                _logBytes, _srvEnabled, _srvPort, _srvHost, _srvSystemId, _srvThreads, _srvWorkerThreads,
                _srvJmxEnabled, _srvJmxDomain, _srvMaxConnections, _srvBindTimeout, _srvStatsPrintPeriod,
                _srvRatePrintPeriod, _srvRatePrintCount, _srvMaxConnectionsPerIp, _srvTlsEnabled,
                _srvTlsPort, _srvTlsHost, _srvTlsKeystorePath, _srvTlsKeystoreAlias, _srvTlsKeystorePass,
                _srvTlsReload, _srvProxyEnabled, _srvProxyPort, _ptrnVreceiver, _forwardDlrs,
                _charsetGsm, _charsetLatin1, _charsetUcs2, _ccat8bit, _defaultMaxRate,
                _defaultMaxConnectionsPerUser, _defaultWindowMonitorInterval, _defaultResponseTimeout,
                _defaultMaxPending, _defaultWriteTimeout, _logPdusExclude, _filtersBeforeInsertMessage,
                _flagReverseDlrSrcDst, _timeoutMillis
        };
        this.authProvider = new BasicSmppAuthenticationProvider(this);
        this.submitProcessor = new BasicSubmitSmProcessor(this);
        configurationProvider.loadDefaultParams(getParamPrefix(), allPrms);
        // Initialize the new BindHandler using our injected interfaces
        this.bindHandler = new SmppServerBindHandler<M>(this, authProvider, submitProcessor);
        this.inEventQueue = new LinkedBlockingQueue<>();
        this.msgRefNumGenerator = new AtomicInteger();
        this.msgPartId = new AtomicInteger(-1);
        totalCounters = new SmppServerSessionCounters();
        configServerRatePrintPeriod();
        configServerRatePrintCount();
        configPtrnReceiver();
        configFlagReverseDlrSrcDst();
        configReassemblingTimeout();
    }

    public void setMessageStore(SmppServerMessageStore<M> messageStore) {
        this.messageStore = messageStore;
    }

    @Override
    public String getType() {
        return TYPE_SMPP_SERVER;
    }

    @Override
    public Thread start() {
        logger.info("starting SmppServer");
        this.keepOnRunning = true;
        stopExecutor(inExecutor, "in");
        inExecutor = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon(false).name(getFullName() + "-SmppServerInExecutor-", 1)
                .uncaughtExceptionHandler((thread, throwable) ->
                        logger.warn("uncaught exception in SmppServerOutSmsWorker:{} inExecutor", getFullName(), throwable))
                .factory());
        inExecutorRunnable = new InTask<M>(this);
        inExecutor.execute(inExecutorRunnable);
        stopExecutor(monitorExecutor, "monitor");
        monitorExecutor = (ScheduledThreadPoolExecutor)
                Executors.newScheduledThreadPool(configurationProvider.getIntPrpt(_srvMonitorThreads),
                        new ThreadFactoryBuilder()
                                .setNameFormat(getFullName() + "-SmppServerSessionWindowMonitorPool-%d")
                                .build());
        stopExecutor(outExecutor, "out");
        outExecutor = new ThreadPoolExecutor(0,
                configurationProvider.getIntPrpt(_srvOutThreads),
                60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                new ThreadFactoryBuilder()
                        .setDaemon(false)
                        .setNameFormat(getFullName() + "-SmppServerOutResponder-%d")
                        .setUncaughtExceptionHandler((thread, throwable) ->
                                logger.warn("uncaught exception in SmppServerOutSmsWorker:{} outExecutor",
                                        getFullName(), throwable))
                        .build());
        configMaxInactivityTime();
        if (messageStore != null) {
            messageStore.start();
        }
        stopExecutor(statsManager, "stats");
        configServerStatsPrintPeriod();

        startStopInternalServers();
        return super.start();
    }

    @Override
    public boolean stop() {
        logger.info("Stopping SMPP server...");
        messagePartsHandler.stop();
        keepOnRunning = false;
        messageStore.stop();
        if (inactivityTimeFuture != null) {
            try {
                inactivityTimeFuture.cancel(true);
            } catch (Exception e) {
                logger.warn("error canceling inactivity time job", e);
            }
        }
        if (inExecutorRunnable != null) {
            inExecutorRunnable.die();
        }
        stopExecutor(inExecutor, "in");
        stopExecutor(monitorExecutor, "monitor");
        stopExecutor(outExecutor, "out");
        destroyServer(server);
        destroyServer(tlsServer);
        destroyServer(proxyServer);
        if (serverStatsScheduledFuture != null) {
            try {
                serverStatsScheduledFuture.cancel(true);
            } catch (Exception e) {
                logger.warn("error canceling server stats job", e);
                serverStatsScheduledFuture = null;
            }
        }
        stopExecutor(statsManager, "stats");
        if (server != null || tlsServer != null || proxyServer != null) {
            logger.info("SMPP server stopped. Server counters: {}. TLS Server counters:{}. PROXY Server counters:{}",
                    server != null ? server.getCounters() : null,
                    tlsServer != null ? tlsServer.getCounters() : null,
                    proxyServer != null ? proxyServer.getCounters() : null);
        }
        return super.stop();
    }

    protected void destroyServer(com.cloudhopper.smpp.SmppServer smppServer) {
        if (smppServer != null && !smppServer.isDestroyed()) {
            try {
                if (isFastUnsafeStop && smppServer instanceof DefaultSmppServer dss) {
                    dss.destroy(0, 1);
                } else {
                    smppServer.destroy();
                }
            } catch (Exception ex) {
                logger.warn("error stopping proxy server. This might be expected, as boss/worker NELGs might have been stopped by plain server", ex);
            }
        }
    }

    @Override
    public boolean isMessageTrackerEnabled() {
        return false;
    }

    public void reloadTlsServer() {
        startStopServer(ServerType.TLS, false);
        startStopServer(ServerType.TLS);
    }

    protected void startStopInternalServers() {
        startStopServer(ServerType.NORMAL);
        startStopServer(ServerType.TLS);
        startStopServer(ServerType.PROXY);
    }

    protected void startStopServer(ServerType type) {
        boolean start = type.isEnabled(configurationProvider, _srvEnabled, _srvTlsEnabled, _srvProxyEnabled);
        startStopServer(type, start);
    }

    protected void startStopServer(ServerType type, boolean start) {
        var srv = type == ServerType.NORMAL ? server : type == ServerType.TLS ? tlsServer : proxyServer;
        final var prefix = type == ServerType.NORMAL ? "" : type == ServerType.TLS ? "tls-" : "proxy-";
        if (start) {
            if (srv != null) {
                logger.warn("not (re)starting {}server", prefix);
                return;
            } else {
                var config = createSmppServerConfiguration(type);
                EventLoopGroup boss;
                EventLoopGroup worker;
                if (Epoll.isAvailable()) {
                    logger.info("Epoll is available! Using epoll event loop group");
                    boss = new EpollEventLoopGroup(configurationProvider.getIntPrpt(_srvThreads),
                            Thread.ofPlatform().name(getFullName() + "-" + prefix + "boss", 1).factory());
                    worker = new EpollEventLoopGroup(configurationProvider.getIntPrpt(_srvWorkerThreads),
                            Thread.ofPlatform().name(getFullName() + "-" + prefix + "worker", 1).factory());
                } else {
                    boss = new NioEventLoopGroup(configurationProvider.getIntPrpt(_srvThreads));
                    worker = new NioEventLoopGroup(configurationProvider.getIntPrpt(_srvWorkerThreads));
                }
                srv = new DefaultSmppServer(config, bindHandler, monitorExecutor, boss, worker);
                try {
                    srv.start();
                } catch (Exception e) {
                    logger.error("ERROR starting {}server", prefix, e);
                }
            }
        } else {
            if (srv != null) {
                try {
                    srv.destroy();
                } catch (Exception e) {
                    logger.warn("error destroying {}server", prefix);
                }
                srv = null;
            }
        }

        if (type == ServerType.TLS) {
            tlsServer = srv;
        } else if (type == ServerType.PROXY) {
            proxyServer = srv;
        } else {
            server = srv;
        }
    }

    protected SmppServerConfiguration createSmppServerConfiguration(ServerType type) {
        var configuration = new SmppServerConfiguration();
        configuration.setName(getFullName() + (type == ServerType.NORMAL ? "" : type == ServerType.TLS ? ".TLS" : ".PROXY"));
        configuration.setSystemId(configurationProvider.getPrpt(_srvSystemId));
        final int port = type.getPort(configurationProvider, _srvPort, _srvTlsPort, _srvProxyPort);
        configuration.setPort(port);
        configuration.setJmxEnabled(configurationProvider.getBlnPrpt(_srvJmxEnabled));
        configuration.setJmxDomain(configurationProvider.getPrpt(_srvJmxDomain));
        configuration.setMaxConnectionSize(configurationProvider.getIntPrpt(_srvMaxConnections));
        configuration.setBindTimeout(configurationProvider.getLongPrpt(_srvBindTimeout));
        configuration.setNonBlockingSocketsEnabled(true);
        configuration.setDefaultRequestExpiryTimeout(configurationProvider.getLongPrpt(_defaultResponseTimeout));
        configuration.setDefaultWindowMonitorInterval(configurationProvider.getLongPrpt(_defaultWindowMonitorInterval));
        configuration.setDefaultWindowSize(configurationProvider.getIntPrpt(_defaultMaxPending));
        configuration.setDefaultWindowWaitTimeout(configuration.getDefaultRequestExpiryTimeout());
        configuration.setDefaultSessionCountersEnabled(true);

        if (type == ServerType.TLS) {
            SslConfiguration sslConfig = new SslConfiguration();
            sslConfig.setKeyStorePath(configurationProvider.getPrpt(_srvTlsKeystorePath));
            sslConfig.setCertAlias(configurationProvider.getPrpt(_srvTlsKeystoreAlias));
            sslConfig.setKeyStorePassword(configurationProvider.getPrpt(_srvTlsKeystorePass));

            sslConfig.setValidatePeerCerts(false);
            sslConfig.setValidateCerts(false);
            sslConfig.setNeedClientAuth(false);

            configuration.setSslConfiguration(sslConfig);
        } else if (type == ServerType.PROXY) {
            configuration.setProxyProtocol(true);
        }

        return configuration;
    }

    public boolean stopExecutor(ExecutorService executor, String name) {
        if (executor == null || executor.isTerminated()) {
            return true;
        }
        if (isFastUnsafeStop) {
            executor.shutdownNow();
        } else {
            executor.shutdown();
        }
        try {
            return executor.awaitTermination(1, TimeUnit.MINUTES);
        } catch (Exception e) {
            logger.warn("error waiting for {} executor to shutdown", name, e);
        }
        return false;
    }

    private void configMaxInactivityTime() {
        long inactivityTime = configurationProvider.getLongPrpt(_srvMaxInactivityTime);
        logger.debug("setting srv.inactivityTime: {}", inactivityTime);

        if (inactivityTimeFuture != null) {
            inactivityTimeFuture.cancel(false);
        }

        if (inactivityTime > 0) {
            inactivityTimeFuture = monitorExecutor.scheduleWithFixedDelay(
                    new InactivityTimeTask(this),
                    inactivityTime + 1, //we need the executor to check for inactive sessions
                    inactivityTime + 1, //with a small delay. That's why we add + 1 minute.
                    TimeUnit.MINUTES);
        } else {
            inactivityTimeFuture.cancel(false);
            inactivityTimeFuture = null;
        }
    }

    private void configServerStatsPrintPeriod() {
        long statisticsPrintPeriod = configurationProvider.getLongPrpt(_srvStatsPrintPeriod);
        logger.debug("setting srv.statisticsPrintPeriod: {}", statisticsPrintPeriod);

        if (serverStatsScheduledFuture != null) {
            serverStatsScheduledFuture.cancel(false);
        }

        if (statisticsPrintPeriod > 0) {
            if (statsManager == null || statsManager.isShutdown()) {
                statsManager = new ScheduledThreadPoolExecutor(
                        1,
                        new ThreadFactoryBuilder()
                                .setDaemon(false)
                                .setNameFormat(getFullName() + "-SmppServerStatsMonitor-%d")
                                .setUncaughtExceptionHandler((thread, throwable) ->
                                        logger.warn("uncaught exception in SmppServer:{} statsManager",
                                                getFullName(), throwable))
                                .build());
                logger.info("started stats monitor thread");
            }

            //re-schedule it now with the new statistics print period
            serverStatsScheduledFuture = statsManager.scheduleWithFixedDelay(
                    new PrintStatisticsTask(this),
                    statisticsPrintPeriod,
                    statisticsPrintPeriod,
                    TimeUnit.SECONDS);
        } else {
            if (statsManager != null) {
                logger.info("Shutting down statsManager {}", statsManager);
                statsManager.shutdown();
                statsManager = null;
            }
        }
    }

    private void configServerRatePrintPeriod() {
        long ratePrintPeriod = configurationProvider.getLongPrpt(_srvRatePrintPeriod);
        logger.debug("setting srv.ratePrintPeriod: {}", ratePrintPeriod);

        totalCounters.setPeriod(ratePrintPeriod);
    }

    private void configServerRatePrintCount() {
        int ratePrintCount = configurationProvider.getIntPrpt(_srvRatePrintCount);
        logger.debug("setting srv.ratePrintCount: {}", ratePrintCount);

        totalCounters.setCount(ratePrintCount);
    }

    private void configPtrnReceiver() {
        logger.debug("setting ptrn.valid.receiver = {}", configurationProvider.getPrpt(_ptrnVreceiver));
    }

    private void configOutThreads() {
        int threads = configurationProvider.getIntPrpt(_srvOutThreads);
        logger.debug("setting maximum out.threads to {}", threads);
        outExecutor.setMaximumPoolSize(threads);
    }

    private void configMonitorThreads() {
        int threads = configurationProvider.getIntPrpt(_srvMonitorThreads);
        logger.debug("setting monitor.threads to {}", threads);
        monitorExecutor.setCorePoolSize(threads);
    }

    private void configMaxRate(String userId, String newValue) {
        try {
            double newRate = Double.parseDouble(newValue);
            bindHandler.configMaxRate(userId);
            logger.debug("configured max rate of user:{} to {}", (userId == null ? "all" : userId), newRate);
        } catch (Exception e) {
            logger.warn("Exception caught trying to parse new max rate value:{} for user:{}. Not propagating change", newValue, userId);
        }
    }

    protected void configMaxConnectionsPerUser(String accountId, String newValue) {
        try {
            int newMax = Integer.parseInt(newValue);
            logger.warn("Max connections per user changed to:{}", newMax);
        } catch (Exception e) {
            logger.warn("Exception caught trying to parse new max connections per user value:{}Not propagating change", newValue);
        }
    }

    protected boolean configFlagReverseDlrSrcDst() {
        flagReverseDlrSrcDst = configurationProvider.getBlnPrpt(_flagReverseDlrSrcDst);
        return flagReverseDlrSrcDst;
    }

    protected void configReassemblingTimeout() {
        long timeout = configurationProvider.getLongPrpt(_timeoutMillis);
        if (timeout > 0) {
            setMessagePartsHandler(new MessagePartsHandler<M>(new CcatMessagePartsEventsListener(), configurationProvider.getLongPrpt(_timeoutMillis)));
            logger.warn("configReassemblingTimeout|warn|set:{}", timeout);
        } else {
            setMessagePartsHandler((new MessagePartsHandler<M>(new CcatMessagePartsEventsListener(), 30000L)));
            logger.warn("configReassemblingTimeout|error|setting|value:{}|default:30000", timeout);
        }
    }

    public void setMessagePartsHandler(MessagePartsHandler<M> messagePartsHandler) {
        this.messagePartsHandler = messagePartsHandler;
    }

    @Override
    public boolean myPropertyChange(String key, String newValue, String oldValue) {
        messageStore.configure(key, newValue, oldValue);
        if (key.equals(_srvOutThreads[0])) {
            configOutThreads();
        } else if (key.equals(_srvMonitorThreads[0])) {
            configMonitorThreads();
        } else if (key.equals(_srvMaxInactivityTime[0])) {
            configMaxInactivityTime();
        } else if (key.equals(_srvStatsPrintPeriod[0])) {
            configServerStatsPrintPeriod();
        } else if (key.equals(_srvRatePrintPeriod[0])) {
            configServerRatePrintPeriod();
        } else if (key.equals(_srvRatePrintCount[0])) {
            configServerRatePrintCount();
        } else if (key.equals(_ptrnVreceiver[0])) {
            configPtrnReceiver();
        } else if (key.equals(_defaultMaxRate[0])) {
            configMaxRate(null, newValue);
        } else if (key.equals(_defaultMaxConnectionsPerUser[0])) {
            configMaxConnectionsPerUser(null, newValue);
        }  else if (key.equals(_srvEnabled[0])) {
            startStopServer(ServerType.NORMAL);
        } else if (key.equals(_srvTlsEnabled[0])) {
            startStopServer(ServerType.TLS);
        } else if (key.equals(_srvProxyEnabled[0])) {
            startStopServer(ServerType.PROXY);
        } else if (key.equals(_srvTlsReload[0])) {
            reloadTlsServer();
        } else if (key.equals(_flagReverseDlrSrcDst[0])) {
            configFlagReverseDlrSrcDst();
        } else if (key.equals(_timeoutMillis[0])) {
            configReassemblingTimeout();
        } else if (key.equals(_logPdus[0]) || key.equals(_logBytes[0]) || key.equals(_logPdusExclude[0])) {
            try {
                final boolean logPdus = getLogPdus();
                final boolean logBytes = getLogBytes();
                bindHandler.connections.connections.forEach((accountId, uc) -> {
                    uc.getAllHandlers().forEach(h -> {
                        var opts = h.getSession().getConfiguration().getLoggingOptions();
                        opts.setLogPdu(logPdus);
                        opts.setLogBytes(logBytes);
                        opts.setExcludeLogPdus(getExcludeLogPdus());
                    });
                });
            } catch (Exception e) {
                logger.warn("error changing logging options", e);
            }
        } else {
            return super.myPropertyChange(key, newValue, oldValue);
        }
        return true;
    }

    public M doMessage(int pThreadIndex, M pMsg) throws IOException {
        boolean isDlr = StandardMessage.MSG_DLR == getMessageType(pMsg);
        if (isDlr && !isForwardDlrs()) {
            return null;
        }

        boolean hasSystemId = !Strings.isNullOrEmpty(pMsg.systemId);
        if ((hasSystemId && !bindHandler.isSystemIdReachable(pMsg.owner_id, pMsg.systemId)) || !bindHandler.isConnectionReachable(pMsg.owner_id)) {
            if (markAsUnpushed(pMsg)) {
                return null;
            }
            return pMsg;
        }

        var handler = bindHandler.getHandlerForSending(pMsg.owner_id, pMsg.systemId);
        var session = handler != null ? handler.getSession() : null;
        if (session == null || !session.isBound()) {
            if (markAsUnpushed(pMsg)) {
                return null;
            }
            return pMsg;
        }

        List<DeliverSm> requests;
        try {
            requests = isDlr ? generateDeliverSmForDLR(pMsg) : generateDeliverSmForMO(pMsg);
        } catch (Exception e) {
            logger.warn("Caught exception while generating SMPP request(s) {}", MessageTrace.identifiers(pMsg), e);
            return isDlr ? null : pMsg;
        }

        if (requests == null || requests.isEmpty()) {
            return isDlr ? null : pMsg;
        }

        for (DeliverSm deliverSm : requests) {
            Object deliverMsgId = deliverSm.getReferenceObject();
            if (deliverMsgId instanceof String msgId) {
                deliverSm.setReferenceObject(new Object[]{handler, pMsg, msgId});
            } else {
                deliverSm.setReferenceObject(new Object[]{handler, pMsg});
            }
            enqueueOut(deliverSm);
            if (MessageTrace.shouldLog(configurationProvider, MessageTrace.EVENT_DELIVER_ENQUEUED)) {
                logger.info("message.deliver.enqueued worker={} {}", getFullName(), MessageTrace.identifiers(pMsg));
            }
        }
        return null;
    }

    protected List<DeliverSm> generateDeliverSmForDLR(M pMsg) {
        Address sender = new Address(SmppConstants.TON_UNKNOWN, SmppConstants.NPI_UNKNOWN, pMsg.from);
        Address receiver = new Address(SmppConstants.TON_UNKNOWN, SmppConstants.NPI_UNKNOWN, pMsg.to);

        if (configurationProvider.getBlnPrpt(_flagReverseDlrSrcDst)) {
            Address tmp = sender;
            sender = receiver;
            receiver = tmp;
        }

        final byte coding = SmppConstants.DATA_CODING_DEFAULT;
        final String charset = getCharsetGsm();
        byte requestDelivery = pMsg.acked ?
                SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED : SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_NOT_REQUESTED;

        final int errorCode = SmppServerUtil.encodeErrorCode(pMsg.errcode != null ? Integer.parseInt(pMsg.errcode) : StandardMessage.DLR_ERR_SUCCESS);

        List<DeliverSm> deliverSms = new ArrayList<>();
        if (pMsg.reassembledParts != null && !pMsg.reassembledParts.isEmpty()) {
            for (String partId : pMsg.reassembledParts) {
                deliverSms.add(getDeliverSm(pMsg, partId, errorCode, sender, receiver, coding, requestDelivery, charset));
            }
        } else {
            String messageId = !Strings.isNullOrEmpty(pMsg.serial) ? pMsg.serial : Integer.toString(pMsg.msgId);
            deliverSms.add(getDeliverSm(pMsg, messageId, errorCode, sender, receiver, coding, requestDelivery, charset));
        }
        return deliverSms;
    }

    protected DeliverSm getDeliverSm(M pMsg, String messageId, int errorCode, Address sender, Address receiver,
                                   byte coding, byte requestDelivery, String charset) {
        var submitDate = ZonedDateTime.now(ZoneOffset.UTC); // Ideally fetch from pMsg if populated
        var doneDate = ZonedDateTime.now(ZoneOffset.UTC);

        final var deliveryReceipt = new DeliveryReceipt(messageId, 1, 1, submitDate, doneDate, SmppServerUtil.encodeFinalState(pMsg.state),
                String.valueOf(errorCode), "DLR");

        DeliverSm deliverSm = new DeliverSm();
        deliverSm.setSourceAddress(sender);
        deliverSm.setDestAddress(receiver);
        deliverSm.setDataCoding(coding);
        deliverSm.setRegisteredDelivery(requestDelivery);
        deliverSm.setPriority((byte) (pMsg.priority >= StandardMessage.LOW_PRIORITY && pMsg.priority <= StandardMessage.HIGH_PRIORITY ?
                pMsg.priority : StandardMessage.NORMAL_PRIORITY));

        try {
            deliverSm.setShortMessage(CharsetUtil.encode(deliveryReceipt.toShortMessage(), charset));
        } catch (SmppInvalidArgumentException e) {
            logger.warn("Caught SmppInvalidArgumentException", e);
            markAsUnpushed(pMsg);
            return null;
        }

        deliverSm.setEsmClass(SmppConstants.ESM_CLASS_MT_SMSC_DELIVERY_RECEIPT);
        deliverSm.setReferenceObject(messageId);
        return deliverSm;
    }

    protected List<DeliverSm> generateDeliverSmForMO(M pMsg) throws SmppInvalidArgumentException {
        Address sender = new Address(SmppConstants.TON_UNKNOWN, SmppConstants.NPI_UNKNOWN, pMsg.from);
        Address receiver = new Address(SmppConstants.TON_UNKNOWN, SmppConstants.NPI_UNKNOWN, pMsg.to);

        byte coding;
        String charset = null;
        byte[][] ccatBodies;

        if (pMsg.type == StandardMessage.MSG_TEXT || pMsg.type == StandardMessage.MSG_UCS2) {
            if (pMsg.type == StandardMessage.MSG_TEXT) {
                charset = getCharsetGsm();
                coding = SmppConstants.DATA_CODING_GSM;
            } else {
                charset = getCharsetUcs2();
                coding = SmppConstants.DATA_CODING_UCS2;
            }

            if (Strings.isNullOrEmpty(pMsg.binheader)) {
                String body = Strings.nullToEmpty(pMsg.body);
                boolean ccat8Bit = configurationProvider.getBlnPrpt(_ccat8bit);
                int msgRefNum = generateMessageReferenceNumber(ccat8Bit);
                ccatBodies = SmppServerUtil.splitMessage(body, charset, msgRefNum, ccat8Bit);
            } else {
                byte[] udh = HexUtil.toByteArray(pMsg.binheader);
                byte[] sm = CharsetUtil.encode(pMsg.body, charset);
                ccatBodies = new byte[1][];
                ccatBodies[0] = SmppServerUtil.mergeByteArrays(udh, sm);
            }
        } else {
            ccatBodies = new byte[1][];
            ccatBodies[0] = HexUtil.toByteArray(Strings.nullToEmpty(pMsg.binheader).concat(pMsg.body));
            coding = SmppConstants.DATA_CODING_8BIT;
        }

        List<DeliverSm> requests = new ArrayList<>();
        for (byte[] shortText : ccatBodies) {
            DeliverSm deliverSm = new DeliverSm();
            deliverSm.setSourceAddress(sender);
            deliverSm.setDestAddress(receiver);
            deliverSm.setRegisteredDelivery(pMsg.acked ?
                    SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED : SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_NOT_REQUESTED);

            if (ccatBodies.length > 1 || (pMsg.binheader != null && !pMsg.binheader.isEmpty())) {
                deliverSm.setEsmClass(SmppConstants.ESM_CLASS_UDHI_MASK);
            }

            deliverSm.setDataCoding(coding);
            deliverSm.setShortMessage(shortText);
            deliverSm.setPriority((byte) (pMsg.priority >= StandardMessage.LOW_PRIORITY && pMsg.priority <= StandardMessage.HIGH_PRIORITY ?
                    pMsg.priority : StandardMessage.NORMAL_PRIORITY));

            if (ccatBodies.length == 1) {
                deliverSm.setReferenceObject(pMsg);
            } else {
                deliverSm.setReferenceObject(generateMessageReferenceForDeliverSm(pMsg, deliverSm, charset, requests.size()));
            }

            int mccMnc = pMsg.cnetwork > 0 ? pMsg.cnetwork : pMsg.onetwork;
            if (mccMnc > 0) {
                deliverSm.setOptionalParameter(new Tlv(VendorSpecificConstants.TAG_SOURCE_NETWORK_ID,
                        ByteArrayUtil.toByteArray(mccMnc), VendorSpecificConstants.TAG_NAME_SOURCE_NETWORK_ID));
            }

            requests.add(deliverSm);
        }
        return requests;
    }

    public int generateMessageReferenceNumber(boolean ccat8bit) {
        int newVal = msgRefNumGenerator.incrementAndGet();
        return ccat8bit ? (byte) newVal : (short) newVal;
    }

    protected M generateMessageReferenceForDeliverSm(M original, DeliverSm deliverSm, String charset, int partNo) {
        M msg;
        try {
            msg = (M) original.clone();
        } catch (CloneNotSupportedException e) {
            logger.warn("unable to clone original message. using the original");
            return original;
        }

        boolean ccat8Bit = configurationProvider.getBlnPrpt(_ccat8bit);
        byte[] sm = deliverSm.getShortMessage();
        byte[] udh = new byte[ccat8Bit ? 6 : 7];
        System.arraycopy(sm, 0, udh, 0, udh.length);
        msg.binheader = HexUtil.toHexString(udh);
        byte[] body = new byte[sm.length - udh.length];
        System.arraycopy(sm, udh.length, body, 0, body.length);
        msg.body = CharsetUtil.decode(body, charset);

        if (partNo > 0) {
            msg.msgId = msgPartId.decrementAndGet();
            if (msg.msgId > 0) {
                synchronized (msgPartId) {
                    if (msgPartId.get() > 0) {
                        msgPartId.set(-1);
                    }
                }
                msg.msgId = msgPartId.decrementAndGet();
            }
        }
        return msg;
    }

    public boolean markAsUnpushed(M msg) {
        return messageStore != null && messageStore.markAsUnpushed(msg);
    }

    public void outTaskFailed(Pdu pdu, M message) {
        if (pdu != null && pdu.isRequest() && pdu.getCommandId() == SmppConstants.CMD_ID_DELIVER_SM) {
            if (message == null) {
                logger.warn("no message found for the failed out pdu {}", MessageTrace.pdu(pdu));
                return;
            }
            if (MessageTrace.shouldLog(configurationProvider, MessageTrace.EVENT_DELIVER_FAILED)) {
                logger.warn("message.deliver.failed worker={} {}", getFullName(), MessageTrace.identifiers(message));
            }
            if (!markAsUnpushed(message)) {
                enqueueNoExceptions(message);
            }
        } else {
            //should there be done something else for responses?
        }
    }

    public void enqueueIn(InEvent<M> ine) {
        if (printMsgs) {
            logger.debug("IN: {}", ine);
        }

        InEvent<M> filtered = handleBeforeInsertMessageFiltering(ine);
        if (filtered == null) {
            return;
        }
        filtered.pMsg.serial = UUID.randomUUID().toString();
        if (MessageTrace.shouldLog(configurationProvider, MessageTrace.EVENT_ACCEPTED)) {
            logger.info("message.accepted ingress=smppserver worker={} {}", getFullName(), MessageTrace.identifiers(filtered.pMsg));
        }
        filtered.waitingForResponse = false;
        filtered.pMsg.ctstamp = ine.localTimestamp.getTime();
        filtered.pMsg.onetwork = ine.mpid;

        // Check reassembling logic
        if (!Strings.isNullOrEmpty(ine.pMsg.binheader)) {
            messagePartsHandler.addMessagePart(ine.pMsg);
            enqueueOut(SmppServerUtil.createSubmitRsp(filtered.submitSm, SmppConstants.STATUS_OK, filtered.pMsg.serial));
            return;
        }
        try {
            enqueueToRouter(ine.pMsg);
            enqueueOut(SmppServerUtil.createSubmitRsp(filtered.submitSm, SmppConstants.STATUS_OK, filtered.pMsg.serial));
        } catch (InterruptedException e) {
            logger.error("Interrupted while waiting for submit RSP", e);
            enqueueOut(SmppServerUtil.createSubmitRsp(filtered.submitSm, SmppConstants.STATUS_UNKNOWNERR, filtered.pMsg.serial));
            throw new RuntimeException(e);
        }
        inEventQueue.add(ine);
    }

    protected boolean checkReassembling(M msg) {
        if (!this.keepOnRunning) {
            return true;
        }

        return true;
    }

    public void reEnqueueIn(List<InEvent<M>> inEvents) {
        inEvents.forEach(event -> enqueueToRouterNoExceptions(event.pMsg));
        inEventQueue.addAll(inEvents);
    }

    public InEvent<M> handleBeforeInsertMessageFiltering(InEvent<M> ine) {
        List<AbstractOutWorker> filters = getBeforeInsertMessageFilters();
        if (filters == null || filters.isEmpty()) {
            return ine;
        }

        for (AbstractOutWorker filter : filters) {
            try {
                filter.doFilter(FilterLifecyclePhase.BEFORE_INSERT, ine.pMsg);
            } catch (FilterException fe) {
                if (fe.getStatusCode() == FilterStatusCodes.DROP) {
                    logger.debug("Dropping message after filtering (before insert) {}", MessageTrace.identifiers(ine.pMsg));
                    enqueueOut(SmppServerUtil.createSubmitRsp(ine.submitSm, VendorSpecificConstants.STATUS_NUMBLOCKED, null));
                    return null;
                }
            } catch (Exception e) {
                logger.warn("exception caught while trying to apply filter:{} before inserting message {}. " +
                        "ignoring filter and continuing", filter.getFullName(), MessageTrace.identifiers(ine.pMsg), e);
            }
        }

        return ine;
    }

    public void enqueueOut(Pdu event) {
        if (printMsgs) {
            logger.debug("OUT: {}", event);
        }
        // using execute instead of submit, so as to not wrap our OutTask runnable with a FutureTask and
        // our PriorityBlockingQueue can prioritize it as needed
        outExecutor.execute(new OutTask<M>(this, event));
    }

    public LinkedBlockingQueue<InEvent<M>> getInEventQueue() {
        return inEventQueue;
    }

    public void checkGetInRequestStats() {
        totalCounters.checkGetInRequestStats();
    }

    public void checkGetInResponseStats() {
        totalCounters.checkGetInResponsesStats();
    }

    public void checkGetOutRequestStats() {
        totalCounters.checkGetOutRequestStats();
    }

    public void checkGetOutResponseStats() {
        totalCounters.checkGetOutResponsesStats();
    }

    public boolean isInEventQueueEmpty() {
        return inEventQueue.isEmpty();
    }

    public String getPtrnValidReceiver() {
        return configurationProvider.getPrpt(_ptrnVreceiver);
    }

    public String getCharsetGsm() {
        return configurationProvider.getPrpt(_charsetGsm);
    }

    public String getCharsetLatin1() {
        return configurationProvider.getPrpt(_charsetLatin1);
    }

    public String getCharsetUcs2() {
        return configurationProvider.getPrpt(_charsetUcs2);
    }

    public String getCharsetBinary() {
        return "UTF-8";
    }

    public int getMaxConnectionsPerIP() {
        try {
            return configurationProvider.getIntPrpt(_srvMaxConnectionsPerIp);
        } catch (Exception e) {
            logger.warn("Exception trying to parse max connections per ip. Will use unlimited (0)");
        }

        return 0;
    }

    public double getMaxRate(String accountId) {
        double res;
        res = Double.valueOf(configurationProvider.getPrpt(_defaultMaxRate));
        if (res <= 0) {
            logger.debug("Translating negative/zero max rate to MAX_VALUE");
            res = Double.MAX_VALUE;
        }

        return res;
    }

    public int getMaxPending() {
        try {
            return Integer.parseInt(configurationProvider.getPrpt(_defaultMaxPending[0]));
        } catch (Exception ex) {
            return ((com.cloudhopper.smpp.impl.DefaultSmppServer) server).getConfiguration().getDefaultWindowSize();
        }
    }

    public long getResponseTimeout() {
        try {
            return Long.parseLong(configurationProvider.getPrpt(_defaultResponseTimeout[0]));
        } catch (Exception ex) {
            return ((com.cloudhopper.smpp.impl.DefaultSmppServer) server).getConfiguration().getDefaultRequestExpiryTimeout();
        }
    }

    public long getWindowMonitorInterval() {
        try {
            return Long.parseLong(configurationProvider.getPrpt(_defaultWindowMonitorInterval[0]));
        } catch (Exception ex) {
            return ((com.cloudhopper.smpp.impl.DefaultSmppServer) server).getConfiguration().getDefaultWindowMonitorInterval();
        }
    }

    public long getWriteTimeout() {
        try {
            return Long.parseLong(configurationProvider.getPrpt(_defaultWriteTimeout[0]));
        } catch (Exception ex) {
            return SmppConstants.DEFAULT_WRITE_TIMEOUT;
        }
    }

    public int getDefaultMaxConnectionPerUser() {
        return configurationProvider.getIntPrpt(_defaultMaxConnectionsPerUser);
    }

    public boolean isForwardDlrs() {
        return configurationProvider.getBlnPrpt(_forwardDlrs);
    }

    public boolean getLogPdus() {
        return configurationProvider.getBlnPrpt(_logPdus);
    }

    public Set<Integer> getExcludeLogPdus() {
        var exclude = configurationProvider.getPrpt(_logPdusExclude);
        if (Strings.isNullOrEmpty(exclude)) {
            return null;
        }
        var cmdIds = exclude.split(",");
        return Arrays.stream(cmdIds).map(Ints::tryParse).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    public boolean getLogBytes() {
        return configurationProvider.getBlnPrpt(_logBytes);
    }

    public int getPort() {
        return configurationProvider.getIntPrpt(_srvPort);
    }

    public Set<Integer> getReachableUsers() {
        return bindHandler.connections.connections.keySet().stream().map(Integer::parseInt).collect(Collectors.toSet());
    }

    public Set<String> getReachableSystemIds() {
        return bindHandler.connections.connections.values().stream()
                .flatMap(c -> c.systemIdTransmittables.keySet().stream())
                .collect(Collectors.toSet());
    }

    public void printServerTotalStatistics() {
        statsLogger.info("{}", getServerTotalStatistics());
    }

    public String getServerTotalStatistics() {
        String totals = server.getCounters().toString();
        if (tlsServer != null) {
            totals += "\nTLS: " + tlsServer.getCounters().toString();
        }
        return totals;
    }

    public SmppServerBindHandler<M> getBindHandler() {
        return bindHandler;
    }

    public SmppServerMessageStore<M> getMessageStore() {
        return messageStore;
    }

    public String getSessionsTotalStatistics() {
        return "[requests_in=" +
                totalCounters.getInRequestStats() +
                " responses_in=" +
                totalCounters.getInResponsesStats() +
                " requests_out=" +
                totalCounters.getOutRequestStats() +
                " responses_out=" +
                totalCounters.getOutResponsesStats() +
                "]";
    }

    public void printSessionsTotalStatistics() {
        String statistics = getSessionsTotalStatistics();
        String[] lines = statistics.split("\\n");
        for (String line : lines) {
            statsLogger.info(line);
        }
    }

    public void printServerConnectionsStatistics() {
        bindHandler.printStatistics(statsLogger);
    }

    public String getServerConnectionsStatistics() {
        return bindHandler.getStatistics();
    }

    public ServerConnections getServerConnections() {
        return bindHandler.getConnections();
    }

    public int getTlsPort() {
        return configurationProvider.getIntPrpt(_srvTlsPort);
    }

    public void checkInactivityTime() {
        int maxInactivityTime = configurationProvider.getIntPrpt(_srvMaxInactivityTime);
        bindHandler.checkInactivityTime(maxInactivityTime);
    }

    public Future<Boolean> persistMessagesIn(List<InEvent<M>> eventsQueue) {
        return messageStore.persistMessages(eventsQueue);
    }

    @Override
    protected FailDelayPolicy createFailDelayPolicy() {
        return new SmppFailDelayPolicy();
    }

    @Override
    public List<AbstractOutWorker> getBeforeInsertMessageFilters() {
        if (beforeInsertMessageFilters == null) {
            this.beforeInsertMessageFilters = new ArrayList<>();
        }
        return beforeInsertMessageFilters;
    }

    @Override
    public void setBeforeInsertMessageFilters(List<AbstractOutWorker> filters) {
        this.beforeInsertMessageFilters = filters;
    }

    @Override
    public String getBeforeInsertMessageFiltersList() {
        return configurationProvider.getPrpt(_filtersBeforeInsertMessage);
    }

    private int getMessageType(StandardMessage msg) throws IOException {
        switch (msg.type) {
            case StandardMessage.MSG_DLR:
            case StandardMessage.MSG_TEXT:
            case StandardMessage.MSG_UCS2:
            case StandardMessage.MSG_PUSH:
            case StandardMessage.MSG_BINARY:
                return msg.type;
            default:
                throw new IOException("Unsupported Message.TYPE: " + msg.type);
        }
    }

    public class CcatMessagePartsEventsListener implements MessagePartsEventsListener<M> {
        @Override
        public void onMessagePartsHandlingEvent(MessagePartsHandler.MessagePartsEventType type, List<M> parts) {

            if (type == MessagePartsHandler.MessagePartsEventType.COMPLETE) {
                //The first part contains the id that we need to send to the provider
                M message = parts.getFirst();
                //we make sure that the filter will not process again the reassembled message
                message.binheader = null;
                message.body = parts.stream().map(m -> m.body).collect(Collectors.joining(""));
                message.reassembledParts = parts.stream().map(m -> m.serial).collect(Collectors.toCollection(ArrayList::new));

                InEvent<M> event = new InEvent<M>(message, null, message.onetwork, new Timestamp(message.ctstamp));
                reEnqueueIn(List.of(event));
            } else {
                var messages = parts.stream().map(m -> new InEvent<M>(m, null, m.onetwork, new Timestamp(m.ctstamp))).collect(Collectors.toList());
                reEnqueueIn(messages);
            }
        }

        @Override
        public String getName() {
            return "SmppServerMessageParts-" + getFullName();
        }
    }

    public enum ServerType {
        NORMAL, TLS, PROXY;

        public boolean isEnabled(SendiumConfigurationProvider cp, String[] normal, String[] tls, String[] proxy) {
            return switch (this) {
                case NORMAL -> cp.getBlnPrpt(normal);
                case TLS -> cp.getBlnPrpt(tls);
                case PROXY -> cp.getBlnPrpt(proxy);
            };
        }

        public int getPort(SendiumConfigurationProvider cp, String[] normal, String[] tls, String[] proxy) {
            return switch (this) {
                case NORMAL -> cp.getIntPrpt(normal);
                case TLS -> cp.getIntPrpt(tls);
                case PROXY -> cp.getIntPrpt(proxy);
            };
        }
    }

    /**
     * {@link FailDelayPolicy} for the {@link SmppServerWorker}.
     * This differs from the default, due to the fact that outgoing messages can be destined to multiple connection,
     * thus blocking due to a bad connection will have a bad effect on other connections.
     * The issue it tries to resolve is a bad connection essentially blocking all outgoing threads, thus messages that
     * can be sent to other connections are also blocked, leading to a very bad throughput for everyone.
     */
    public static class SmppFailDelayPolicy extends FailDelayPolicy {
        private final FailDelayPolicyAction reEnqueueDelayed;
        private final FailDelayPolicyAction reEnqueueRouter;

        public SmppFailDelayPolicy() {
            reEnqueueDelayed = new FailDelayPolicyAction(FailDelayPolicyAction.Action.RE_ENQUEUE_WORKER_DELAYED, 0, 0);
            reEnqueueRouter = new FailDelayPolicyAction(FailDelayPolicyAction.Action.RE_ENQUEUE_ROUTER, 0, 0);
        }

        public FailDelayPolicyAction getActionForMessage(StandardMessage msg, Stage stage, int trial) {
            switch (stage) {
                case WORKER_RETRY:
                    return onWorkerRetry(msg, trial);
                case WORKER_END_RETRY:
                    return onWorkerEndRetry(msg, trial);
                default:
                    return onWorkerRetry(msg, trial);
            }
        }

        private FailDelayPolicyAction onWorkerRetry(StandardMessage msg, int trial) {
            //Can't do anything right now, re-enqueue and try again later
            return reEnqueueDelayed;
        }

        private FailDelayPolicyAction onWorkerEndRetry(StandardMessage msg, int trial) {
            //Too many failures for owner, re-enqueue to router to allow others to continue
            return reEnqueueRouter;
        }
    }
}
