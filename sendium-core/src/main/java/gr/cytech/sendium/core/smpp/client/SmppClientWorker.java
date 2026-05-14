package gr.cytech.sendium.core.smpp.client;

import com.cloudhopper.commons.charset.Charset;
import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.commons.util.HexUtil;
import com.cloudhopper.commons.util.StringUtil;
import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.pdu.BaseSm;
import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.EnquireLink;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.ssl.SslConfiguration;
import com.cloudhopper.smpp.tlv.Tlv;
import com.cloudhopper.smpp.type.Address;
import com.cloudhopper.smpp.type.SmppInvalidArgumentException;
import com.cloudhopper.smpp.util.DeliveryReceipt;
import com.cloudhopper.smpp.util.SmppUtil;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import gr.cytech.sendium.conf.SendiumConfigurationProvider;
import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.queue.Queue;
import gr.cytech.sendium.core.smpp.SmppConnectionManager;
import gr.cytech.sendium.core.smpp.util.CustomCharset;
import gr.cytech.sendium.core.smpp.util.SmppServerUtil;
import gr.cytech.sendium.core.smpp.util.VFGRCharset;
import gr.cytech.sendium.core.worker.ForwardMoService;
import gr.cytech.sendium.core.worker.Tracker;
import gr.cytech.sendium.core.worker.WorkerType;
import gr.cytech.sendium.external.HealthCheckReport;
import gr.cytech.sendium.external.WorkerResourceProvider;
import gr.cytech.sendium.util.MessageFlexValue;
import gr.cytech.sendium.util.SecurityUtils;
import gr.cytech.sendium.util.TimeUtils;
import jakarta.enterprise.context.Dependent;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static gr.cytech.sendium.core.smpp.client.SmppClientWorker.TYPE_SMPP_CLIENT;

@Dependent
@WorkerType(TYPE_SMPP_CLIENT)
public class SmppClientWorker<M extends StandardMessage> extends AbstractOutWorker<M> {
    public static final String TYPE_SMPP_CLIENT = "smppclient";
    public static final String CHARSET_HEX = "HEX";

    public final AtomicInteger msgRefNumGenerator = new AtomicInteger();
    public ScheduledThreadPoolExecutor reconnectionExecutor;
    public boolean connectionHealthCheck;

    static {
        CharsetUtil.charsets.put("ISO-10646-UCS-2", CharsetUtil.CHARSET_UCS_2);
        CharsetUtil.charsets.put("VFGR", new VFGRCharset());
    }

    protected final String[][] prms = {
            {"host", "localhost"}//0
            , {"port", "27777"}
            , {"username", "smsp"}
            , {"password", "psms"}
            , {"extra.hosts", ""}
            , {"backup.hosts", ""}
            , {"src.addr.ton", "2"} //todo enter correct default values for src and dest ton and npi
            , {"src.addr.npi", "1"}
            , {"src.addr.autodetect", "true"}
            //This is an unspoken convention that we use this(2) as default (use kannel defaults values even though 1 is for International numbers)
            , {"dest.addr.ton", "2"}
            , {"dest.addr.npi", "1"}
            , {"reconnection.threads", "1"}
            , {"connections.transceivers", "1"}
            , {"connections.transmitters", "0"}
            , {"connections.receivers", "0"}
            , {"windowSize", "100"}
            , {"con.tout", "10000"}
            , {"request.tout", "30000"}
            , {"window.monitor.interval.millis", "15000"}
            , {"enquire.link.interval.millis", "30000"}
            , {"enquire.link.consecutiveErrors", "1"}
            , {"enquire.link.noTrafficOnly", "false"}
            , {"reconnect.interval.millis", "60000"}
            , {"unbind.timeout.millis", "5000"}
            , {"counters", "true"}
            , {"log.bytes", "false"}
            , {"log.pdus", "true"}
            , {"log.pdus.exclude", SmppConstants.CMD_ID_ENQUIRE_LINK + "," + SmppConstants.CMD_ID_ENQUIRE_LINK_RESP}
            , {"print.resps", "true"}
            , {"print.mos", "true"}
            , {"systemType", ""}
            , {"addressRangeTon", ""}
            , {"addressRangeNpi", ""}
            , {"addressRange", ""}
            , {"interfaceVersion", String.valueOf(SmppConstants.VERSION_3_4)} //52->3.4 51->3.3
            , {"service", ""}
            , {"priority", ""}
            , {"esm.class", ""}
            , {"esm.class.override", "false"}
            // WARNING: "GSM" charset works with CharsetUtil, not with new String(bytes, charset)
            // this is why our SmppServer defaults to GSM7 for gsm charset, because it uses new String
            , {"dcs.charset.map",
            SmppConstants.DATA_CODING_DEFAULT + "_" + CharsetUtil.NAME_GSM + "," +
                    SmppConstants.DATA_CODING_GSM + "_" + CharsetUtil.NAME_GSM + "," +
                    SmppConstants.DATA_CODING_LATIN1 + "_" + CharsetUtil.NAME_ISO_8859_1 + "," +
                    SmppConstants.DATA_CODING_UCS2 + "_" + CharsetUtil.NAME_UCS_2 + "," +
                    SmppConstants.DATA_CODING_8BIT + "_" + CHARSET_HEX}
            , {"dcs.charset.ext", ""} //should be in the format: <DCS1>_<CHARSET-NAME1>,<DCS2>_<CHARSET-NAME2>
            , {"msgType.dcs.map", StandardMessage.MSG_TEXT + "_" + SmppConstants.DATA_CODING_DEFAULT + "," +
                      StandardMessage.MSG_UCS2 + "_" + SmppConstants.DATA_CODING_UCS2 + "," + StandardMessage.MSG_BINARY + "_" + SmppConstants.DATA_CODING_8BIT}
            , {"dlr.charset.fixed", ""} //if we want to use a fixed character set for all DLRs
            , {"ccat.8bit", "true"} //true: use 8-bit ccat false: use 16-bit ccat
            , {"msg.id.type", Integer.toString(MsgIdType.StringLiteral.ordinal())}
            , {"status.retry.worker", SmppConstants.STATUS_MSGQFUL + "," + SmppConstants.STATUS_THROTTLED}
            , {"status.retry.router", ""}
            , {"status.retry.router.removeHlr", "true"}
            , {"status.fail", ""}
            , {"status.default", NackHandlePolicy.RETRY_WORKER.name()}
            //a comma-separated list of <tag_name>_<tag_key_as_short> (e.g. tlv_1400)
            , {"registered.tlvs.submit", ""}
            , {"registered.tlvs.mo", ""}
            , {"registered.tlvs.dlr", ""}
            , {"msg.hash.prefix", ""}
            , {"ssl", "false"}
            , {"ssl.trustAll", "false"}
            , {"dlr.errcodes", ""} //comma-separated list of gatewayErrorCode_ourErrorCode: e.g. 0_0,1000_1
            , {"resp.errcodes", ""} //comma-separated list of gatewayErrorCode_ourErrorCode: e.g. 0_0,1000_1
            , {"local.bind.host", ""}
            , {"connection.healthcheck", "false"}
            , {"reconnection.stability.threshold.millis", "5000"}
            , {"forward.mo.url", ""}
            , {"forward.mo.format", "JSON"}
    };

    //sessionHandlers contains all connections, no matter what type
    protected final SmppConnectionManager<SmppClientSessionHandler> sessionHandlers = new SmppConnectionManager<>();
    protected boolean retryRouterRemoveHlr;
    protected ImmutableMap<Byte, String> codingCharsetMap;
    protected ImmutableBiMap<Integer, Byte> codingMsgTypeMap;
    protected String fixedDlrCharset;
    protected ImmutableMap<String, String> dlrErrCodeMap;
    protected ImmutableMap<String, String> respErrCodeMap;
    protected ScheduledFuture<?> checkConnectionsFuture;
    protected ScheduledFuture<?> enquireLinksFuture;
    protected DefaultSmppClient smppClient;

    private int prmsIndex = 0;
    public final String[] _host = prms[prmsIndex++];
    public final String[] _port = prms[prmsIndex++];
    public final String[] _user = prms[prmsIndex++];
    public final String[] _pass = prms[prmsIndex++];
    public final String[] _extraHosts = prms[prmsIndex++];
    public final String[] _backupHosts = prms[prmsIndex++];
    public final String[] _srcAddrTon = prms[prmsIndex++];
    public final String[] _srcAddrNpi = prms[prmsIndex++];
    public final String[] _srcAddrAutodetect = prms[prmsIndex++];
    public final String[] _destAddrTon = prms[prmsIndex++];
    public final String[] _destAddrNpi = prms[prmsIndex++];
    public final String[] _reconnectionThreads = prms[prmsIndex++];
    public final String[] _transceivers = prms[prmsIndex++];
    public final String[] _transmitters = prms[prmsIndex++];
    public final String[] _receivers = prms[prmsIndex++];
    public final String[] _windowSize = prms[prmsIndex++];
    public final String[] _conTout = prms[prmsIndex++];
    public final String[] _requestTout = prms[prmsIndex++];
    public final String[] _windowMonitorInterval = prms[prmsIndex++];
    public final String[] _enquireLinkInterval = prms[prmsIndex++];
    public final String[] _enquireLinkErrors = prms[prmsIndex++];
    public final String[] _enquireLinkNoTrafficOnly = prms[prmsIndex++];
    public final String[] _reconnectInterval = prms[prmsIndex++];
    public final String[] _unbindTimeout = prms[prmsIndex++];
    public final String[] _counters = prms[prmsIndex++];
    public final String[] _logBytes = prms[prmsIndex++];
    public final String[] _logPdus = prms[prmsIndex++];
    public final String[] _logPdusExclude = prms[prmsIndex++];
    public final String[] _printResps = prms[prmsIndex++];
    public final String[] _printMos = prms[prmsIndex++];
    public final String[] _systemType = prms[prmsIndex++];
    public final String[] _addressRangeTon = prms[prmsIndex++];
    public final String[] _addressRangeNpi = prms[prmsIndex++];
    public final String[] _addressRange = prms[prmsIndex++];
    public final String[] _interfaceVersion = prms[prmsIndex++];
    public final String[] _service = prms[prmsIndex++];
    public final String[] _priority = prms[prmsIndex++];
    public final String[] _esmclass = prms[prmsIndex++];
    public final String[] _esmclassOverride = prms[prmsIndex++];
    public final String[] _codingsCharsets = prms[prmsIndex++];
    public final String[] _codingsCharsetsExtensions = prms[prmsIndex++];
    public final String[] _codingMsgType = prms[prmsIndex++];
    public final String[] _dlrCharsetFixed = prms[prmsIndex++];
    public final String[] _ccat8bit = prms[prmsIndex++];
    public final String[] _msgIdType = prms[prmsIndex++];
    public final String[] _statusRetryWorker = prms[prmsIndex++];
    public final String[] _statusRetryRouter = prms[prmsIndex++];
    public final String[] _statusRetryRouterRemoveHlr = prms[prmsIndex++];
    public final String[] _statusFail = prms[prmsIndex++];
    public final String[] _statusDefault = prms[prmsIndex++];
    public final String[] _tlvsSubmit = prms[prmsIndex++];
    public final String[] _tlvsMo = prms[prmsIndex++];
    public final String[] _tlvsDlr = prms[prmsIndex++];
    public final String[] _msgHashPrefix = prms[prmsIndex++];
    public final String[] _ssl = prms[prmsIndex++];
    public final String[] _sslTrustAll = prms[prmsIndex++];
    public final String[] _dlrErrCodes = prms[prmsIndex++];
    public final String[] _respErrCodes = prms[prmsIndex++];
    public final String[] _localBindHost = prms[prmsIndex++];
    public final String[] _connectionHealthCheck = prms[prmsIndex++];
    protected final String[] _reconnectionStabilityThreshold = prms[prmsIndex++];
    public final String[] _forwardMoUrl = prms[prmsIndex++];
    public final String[] _forwardMoFormat = prms[prmsIndex];

    private MessageFlexValue serviceType;
    private MessageFlexValue priority;
    private byte esmClass;
    private boolean esmClassOverride;
    private boolean srcAddrAutodetect;
    private byte srcAddrTon;
    private byte srcAddrNpi;
    private byte destAddrTon;
    private byte destAddrNpi;
    private MsgIdType msgIdType;
    private boolean printResps;
    private boolean printMos;
    private ImmutableSet<Integer> retryRouterStatusCodes;
    private ImmutableSet<Integer> retryWorkerStatusCodes;
    private ImmutableSet<Integer> failStatusCodes;
    private NackHandlePolicy defaultNackHandlePolicy;
    private ImmutableMap<String, Short> tlvsSubmit;
    private ImmutableMap<Short, String> tlvsMos;
    private ImmutableMap<Short, String> tlvsDlrs;
    private String messageHashPrefix;
    private HealthCheckReport healthCheckReport;

    //for fast initialization
    protected SmppClientWorker() {
    }

    //for tests only
    protected SmppClientWorker(
            SendiumConfigurationProvider configurationProvider,
            Queue routerQ,
            ScheduledThreadPoolExecutor reconnectionExecutor) {
        super(configurationProvider, routerQ);
        configurationProvider.loadDefaultParams(prms);
        suspendAuto = true;

        this.reconnectionExecutor = reconnectionExecutor;
        configServiceType();
        configPriority();
        configMsgIdType();
        configCharsetsAndCodings();
        configMessageTypesToDataCodings();
        configResponseStatusCodesHandling();
        configRegisteredTlvs();
        configPrintLevels();
        configMessageHashPrefix();
        configDlrsErrorCodeMapping();
        configRespErrorCodeMapping();
        configEsmClassOverride();
    }

    public SmppClientWorker(SendiumConfigurationProvider configurationProvider, String instName, Queue routerQ) {
        super(configurationProvider, instName, routerQ);
        prependInstanceName(prms);
        configurationProvider.loadDefaultParams(prms);

        suspendAuto = true;

        configSrcAddress();
        configConnectionHealthCheck();
        configDestAddress();
        configServiceType();
        configPriority();
        configEsmClass();
        configMsgIdType();
        configCharsetsAndCodings();
        configMessageTypesToDataCodings();
        configFixedDlrCharset();
        configResponseStatusCodesHandling();
        configRegisteredTlvs();
        configPrintLevels();
        configDlrsErrorCodeMapping();
        configRespErrorCodeMapping();
        configEsmClassOverride();

        reconnectionExecutor = new ScheduledThreadPoolExecutor(configurationProvider.getIntPrpt(_reconnectionThreads),
                Thread.ofVirtual().name(getFullName() + "-reconnectionThread-", 1)
                        .uncaughtExceptionHandler((t, e) -> logger.warn("uncaught exception in reconnectionExecutor {}", getFullName(), e))
                        .factory());

        configMessageHashPrefix();

        checkAndGetHealthCheckReport();
        logger.debug("{} started", getFullName());
    }

    public void setupInstance(SendiumConfigurationProvider cp, String instName, Queue<M> routerQueue) {
        super.setupInstance(cp, instName, routerQueue);
        prependInstanceName(prms);
        configurationProvider.loadDefaultParams(prms);

        suspendAuto = true;

        configSrcAddress();
        configConnectionHealthCheck();
        configDestAddress();
        configServiceType();
        configPriority();
        configEsmClass();
        configMsgIdType();
        configCharsetsAndCodings();
        configMessageTypesToDataCodings();
        configFixedDlrCharset();
        configResponseStatusCodesHandling();
        configRegisteredTlvs();
        configPrintLevels();
        configDlrsErrorCodeMapping();
        configRespErrorCodeMapping();
        configEsmClassOverride();

        reconnectionExecutor = new ScheduledThreadPoolExecutor(configurationProvider.getIntPrpt(_reconnectionThreads),
                Thread.ofVirtual().name(getFullName() + "-reconnectionThread-", 1)
                        .uncaughtExceptionHandler((t, e) -> logger.warn("uncaught exception in reconnectionExecutor {}", getFullName(), e))
                        .factory());

        configMessageHashPrefix();

        checkAndGetHealthCheckReport();
    }

    @Override
    public void init(WorkerResourceProvider workerResourceProvider, Tracker<M> tracker) {
        super.init(workerResourceProvider, tracker);
        smppClient = workerResourceProvider.getSmppClientHolder().getSmppClient();
    }

    @Override
    public String getType() {
        return TYPE_SMPP_CLIENT;
    }

    @Override
    public boolean isMessageHandlingSynchronous() {
        return false;
    }

    @Override
    public Thread start() {
        // first connection check should be immediate (before worker threads start), so as to avoid losing
        // messages due to suspension for not having established connections
        suspendAuto = false;
        checkConnections();
        if (!isKeepOnRunning()) {
            // our check resulted in becoming disabled, skip start at all
            return null;
        }
        scheduleConnectionsCheck();
        schedulePeriodicEnquireLinks();
        return super.start();
    }

    @Override
    public boolean stop() {
        try {
            reconnectionExecutor.shutdownNow();
        } catch (Exception e) {
            logger.warn("error shutting down internal executors", e);
        }

        if (!sessionHandlers.isEmpty()) {
            try (var shutdownExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(getFullName() + "-shutdown-", 0).factory())) {
                List<Future<?>> shutdownTasks = new ArrayList<>();
                for (final SmppClientSessionHandler handler : sessionHandlers.getAllHandlers()) {
                    shutdownTasks.add(shutdownExecutor.submit(
                            (Runnable) () -> removeConnection(handler, true)));
                }

                for (var shutdownTask : shutdownTasks) {
                    try {
                        shutdownTask.get(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        logger.warn("Too much time waiting for connection to shutdown (more than 10 seconds)");
                    }
                }
                shutdownExecutor.shutdownNow();
            }
        }

        return super.stop();
    }

    @Override
    public String getHashedMessageID(String messageId) {
        return SecurityUtils.generateMD5(messageHashPrefix.concat(messageId));
    }

    @Override
    public boolean myPropertyChange(String key, String newValue, String oldValue) {
        if (key.equals(_srcAddrAutodetect[0]) ||
                key.equals(_srcAddrTon[0]) ||
                key.equals(_srcAddrNpi[0])) {
            configSrcAddress();
        } else if (key.equals(_destAddrTon[0]) ||
                key.equals(_destAddrNpi[0])) {
            configDestAddress();
        } else if (key.equals(_enquireLinkInterval[0])) {
            schedulePeriodicEnquireLinks();
        } else if (key.equals(_connectionHealthCheck[0])) {
            configConnectionHealthCheck();
        } else if (key.equals(_reconnectInterval[0]) ||
                key.equals(_transceivers[0]) ||
                key.equals(_transmitters[0]) ||
                key.equals(_receivers[0])) {
            scheduleConnectionsCheck();
        } else if (key.equals(_printResps[0])) {
            configPrintResps();
        } else if (key.equals(_printMos[0])) {
            configPrintMos();
        } else if (key.equals(_esmclass[0])) {
            configEsmClass();
        } else if (key.equals(_esmclassOverride[0])) {
            configEsmClassOverride();
        } else if (key.equals(_service[0])) {
            configServiceType();
        } else if (key.equals(_priority[0])) {
            configPriority();
        } else if (key.equals(_msgIdType[0])) {
            configMsgIdType();
        } else if (key.equals(_codingsCharsets[0]) || key.equals(_codingsCharsetsExtensions[0])) {
            configCharsetsAndCodings();
        } else if (key.equals(_codingMsgType[0])) {
            configMessageTypesToDataCodings();
        } else if (key.equals(_dlrCharsetFixed[0])) {
            configFixedDlrCharset();
        } else if (key.equals(_statusRetryWorker[0]) ||
                key.equals(_statusRetryRouter[0]) ||
                key.equals(_statusFail[0]) ||
                key.equals(_statusDefault[0]) ||
                key.equals(_statusRetryRouterRemoveHlr[0])
        ) {
            configResponseStatusCodesHandling();
        } else if (key.equals(_tlvsSubmit[0])) {
            configSubmitTlvs();
        } else if (key.equals(_tlvsMo[0])) {
            configMoTlvs();
        } else if (key.equals(_tlvsDlr[0])) {
            configDlrTlvs();
        } else if (key.equals(_dlrErrCodes[0])) {
            configDlrsErrorCodeMapping();
        } else if (key.equals(_respErrCodes[0])) {
            configRespErrorCodeMapping();
        } else if (key.equals(_logPdus[0]) || key.equals(_logBytes[0]) || key.equals(_logPdusExclude[0])) {
            for (var h : sessionHandlers.getAllHandlers()) {
                var opts = h.getSession().getConfiguration().getLoggingOptions();
                opts.setLogPdu(configurationProvider.getBlnPrpt(_logPdus));
                opts.setLogBytes(configurationProvider.getBlnPrpt(_logBytes));
                opts.setExcludeLogPdus(getExcludeLogPdus());
            }
        } else {
            return super.myPropertyChange(key, newValue, oldValue);
        }
        return true;
    }

    @Override
    public boolean isPause() {
        return super.isPause() || !verifyConnectivity();
    }

    @Override
    public M doMessage(int pThreadIndex, M pMsg) throws IOException {
        if (suspendAuto) {
            return handleMessageDuringSuspension(pMsg);
        }
        List<SubmitSm> requests;
        try {
            requests = generateSubmitRequest(pMsg);
        } catch (Exception e) {
            logger.warn("Caught exception while generating SMPP request(s) for msg:{}", pMsg, e);
            return pMsg;
        }
        SmppClientSessionHandler handler = getAvailableHandlerForSending();
        for (int i = 0; i < requests.size(); i++) {
            SubmitSm submitSm = requests.get(i);
            try {
                handler.getSession().sendRequestPdu(submitSm, configurationProvider.getLongPrpt(_requestTout), false);
            } catch (Exception e) {
                if (i == 0) {
                    logger.warn("Caught exception while sending 1st of {} parts for msg. Cancelling msg to be retried: {}",
                            requests.size(), pMsg);
                    return pMsg;
                } else {
                    StandardMessage msgPart = (StandardMessage) submitSm.getReferenceObject();
                    logger.warn("Caught exception while sending {} of {} parts for msg. It is re-enqueued as: {}",
                            i, requests.size(), msgPart);
                    enqueueNoExceptions((M) msgPart);
                }
            }
        }

        return null;
    }

    public boolean checkConnectivity() {
        if (connectionHealthCheck) {
            return this.verifyConnectivity();
        }
        return true;
    }

    protected void configSrcAddress() {
        srcAddrAutodetect = configurationProvider.getBlnPrpt(_srcAddrAutodetect);
        srcAddrTon = (byte) configurationProvider.getIntPrpt(_srcAddrTon);
        srcAddrNpi = (byte) configurationProvider.getIntPrpt(_srcAddrNpi);
    }

    protected void configConnectionHealthCheck() {
        connectionHealthCheck = configurationProvider.getBlnPrpt(_connectionHealthCheck);
    }

    protected void configDestAddress() {
        destAddrTon = (byte) configurationProvider.getIntPrpt(_destAddrTon);
        destAddrNpi = (byte) configurationProvider.getIntPrpt(_destAddrNpi);
    }

    protected void configServiceType() {
        String serviceConfig = configurationProvider.getPrpt(_service);
        serviceType = Strings.isNullOrEmpty(serviceConfig) ? null : new MessageFlexValue(serviceConfig);
        logger.debug("serviceType was configured as: {}", serviceType);
    }

    protected void configPriority() {
        String priorityConfig = configurationProvider.getPrpt(_priority);
        priority = Strings.isNullOrEmpty(priorityConfig) ? null : new MessageFlexValue(priorityConfig);
        logger.debug("priority was configured as: {}", priority);
    }

    protected void configPrintResps() {
        printResps = configurationProvider.getBlnPrpt(_printResps);
        logger.debug("configured print.resps to: {}", printResps);
    }

    protected void configPrintMos() {
        printMos = configurationProvider.getBlnPrpt(_printMos);
        logger.debug("configured print.mos to: {}", printMos);
    }

    protected void configPrintLevels() {
        configPrintResps();
        configPrintMos();
    }

    protected void configEsmClassOverride() {
        esmClassOverride = configurationProvider.getBlnPrpt(_esmclassOverride);
    }

    protected void configEsmClass() {
        String esm = configurationProvider.getPrpt(_esmclass);
        if (esm == null || esm.isEmpty()) {
            esmClass = 0x03;
        } else {
            try {
                int esmInt = Integer.parseInt(esm);
                esmClass = (byte) esmInt;
            } catch (Exception e) {
                esmClass = 0x03;
                logger.warn("Exception parsing esm class value, setting it to default (3)");
            }
        }

        logger.debug("Esm class was configured as: {}", esmClass);
    }

    public void configMsgIdType() {
        String idType = configurationProvider.getPrpt(_msgIdType);
        if (idType == null || idType.isEmpty()) {
            msgIdType = MsgIdType.StringLiteral;
        } else {
            try {
                int msgIdTypeInt = Integer.parseInt(idType);
                msgIdType = MsgIdType.values()[msgIdTypeInt];
            } catch (Exception e) {
                msgIdType = MsgIdType.StringLiteral;
                logger.warn("Exception parsing msg id type value, setting it to default (4)");
            }
        }

        logger.debug("Msg ID Type was configured as: {} - {}", msgIdType.ordinal(), msgIdType.name());
    }

    public void configFixedDlrCharset() {
        String charset = configurationProvider.getPrpt(_dlrCharsetFixed);
        fixedDlrCharset = Strings.emptyToNull(charset);
        logger.debug("configured fixed dlr charset to: {}", fixedDlrCharset);
    }

    public void configCharsetsAndCodings() {
        Map<Byte, String> map = parseCodingCharsetString(configurationProvider.getPrpt(_codingsCharsets));
        if (map.isEmpty()) {
            logger.warn("error creating default mapping of coding schemes to character sets! switching to default: {}", _codingsCharsets[1]);
            map = parseCodingCharsetString(_codingsCharsets[1]);
        }
        Map<Byte, String> overrides = parseCodingCharsetString(configurationProvider.getPrpt(_codingsCharsetsExtensions));
        if (!overrides.isEmpty()) {
            map.putAll(overrides);
        }
        this.codingCharsetMap = ImmutableMap.copyOf(map);
        logger.debug("configured coding -> charsets to: {}", codingCharsetMap);
    }

    public String getCharsetForDcs(byte dcs) {
        String charset = codingCharsetMap.get(dcs);
        if (charset == null) {
            charset = codingCharsetMap.get(SmppConstants.DATA_CODING_DEFAULT);
            if (charset == null) {
                charset = codingCharsetMap.get(SmppConstants.DATA_CODING_GSM);
                if (charset == null) {
                    charset = CharsetUtil.NAME_GSM;
                }
            }
        }
        return charset;
    }

    protected void configMessageTypesToDataCodings() {
        Map<Integer, Byte> map = new HashMap<>();
        String prop = configurationProvider.getPrpt(_codingMsgType);
        String[] mappings = prop.split(",");
        for (String mapping : mappings) {
            String[] typeDcs = mapping.split("_");
            try {
                map.put(Integer.parseInt(typeDcs[0]), Byte.parseByte(typeDcs[1]));
            } catch (Exception e) {
                logger.warn("error trying to parse message type to data coding mapping: {}", mapping, e);
            }
        }
        codingMsgTypeMap = ImmutableBiMap.copyOf(map);
    }

    public byte getDcsForMessageType(int msgType) {
        Byte dcs = codingMsgTypeMap.get(msgType);
        if (dcs == null) {
            dcs = codingMsgTypeMap.get(StandardMessage.MSG_TEXT);
            if (dcs == null) {
                dcs = SmppConstants.DATA_CODING_DEFAULT;
            }
        }
        return dcs;
    }

    public int getMessageTypeFromDcs(byte dcs) {
        ImmutableBiMap<Byte, Integer> inv = codingMsgTypeMap.inverse();
        Integer res = inv.get(dcs);
        if (res == null) {
            res = inv.get(SmppConstants.DATA_CODING_DEFAULT);
            if (res == null) {
                res = inv.get(SmppConstants.DATA_CODING_GSM);
                if (res == null) {
                    res = StandardMessage.MSG_TEXT;
                }
            }
        }

        return res;
    }

    public void configResponseStatusCodesHandling() {
        configRetryRouterStatusCodes();
        configRetryWorkerStatusCodes();
        configFailStatusCodes();
        configDefaultNackHandlePolicy();
    }

    public void configRetryRouterStatusCodes() {
        this.retryRouterStatusCodes = parseStatusCodes(configurationProvider.getPrpt(_statusRetryRouter));
        this.retryRouterRemoveHlr = configurationProvider.getBlnPrpt(_statusRetryRouterRemoveHlr);
    }

    public void configRetryWorkerStatusCodes() {
        this.retryWorkerStatusCodes = parseStatusCodes(configurationProvider.getPrpt(_statusRetryWorker));
    }

    public void configFailStatusCodes() {
        this.failStatusCodes = parseStatusCodes(configurationProvider.getPrpt(_statusFail));
    }

    private ImmutableSet<Integer> parseStatusCodes(String statusCodes) {
        if (Strings.isNullOrEmpty(statusCodes)) {
            return ImmutableSet.of();
        } else {
            String[] statuses = statusCodes.split(",");
            Set<Integer> codes = new HashSet<>(statuses.length);
            for (String code : statuses) {
                try {
                    codes.add(Integer.parseInt(code.trim()));
                } catch (Exception e) {
                    logger.warn("error parsing status code: {}. It will be skipped", code);
                }
            }
            return ImmutableSet.copyOf(codes);
        }
    }

    public void configDefaultNackHandlePolicy() {
        String policy = configurationProvider.getPrpt(_statusDefault);
        try {
            this.defaultNackHandlePolicy = NackHandlePolicy.valueOf(policy.toUpperCase());
        } catch (Exception e) {
            this.defaultNackHandlePolicy = NackHandlePolicy.valueOf(_statusDefault[1]);
            logger.warn("error parsing error status handle policy: '{}', switching to default: '{}'",
                    policy, defaultNackHandlePolicy.name());
        }
    }

    public void configRegisteredTlvs() {
        configSubmitTlvs();
        configMoTlvs();
        configDlrTlvs();
    }

    protected void configSubmitTlvs() {
        String tlvCsv = configurationProvider.getPrpt(_tlvsSubmit);
        this.tlvsSubmit = ImmutableMap.copyOf(parseTlvs(tlvCsv)
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey)));
        logger.debug("configured submit tlvs from property:{} to: {}", tlvCsv, tlvsSubmit);
    }

    protected void configMoTlvs() {
        String tlvCsv = configurationProvider.getPrpt(_tlvsMo);
        this.tlvsMos = parseTlvs(tlvCsv);
        logger.debug("configured mo tlvs from property:{} to: {}", tlvCsv, tlvsMos);
    }

    protected void configDlrTlvs() {
        String tlvCsv = configurationProvider.getPrpt(_tlvsDlr);
        this.tlvsDlrs = parseTlvs(configurationProvider.getPrpt(_tlvsDlr));
        logger.debug("configured dlr tlvs from property:{} to: {}", tlvCsv, tlvsDlrs);
    }

    private ImmutableMap<Short, String> parseTlvs(String tlvCsv) {
        if (Strings.isNullOrEmpty(tlvCsv)) {
            return ImmutableMap.of();
        }

        String[] tlvs = tlvCsv.split(",");
        ImmutableMap.Builder<Short, String> tlvMapBuilder = new ImmutableMap.Builder<>();
        for (String tlv : tlvs) {
            try {
                String trimmed = tlv.trim();
                short tag = Short.parseShort(trimmed.split("_")[1]);
                tlvMapBuilder.put(tag, trimmed);
            } catch (Exception e) {
                logger.warn("Error trying to parse registered tlv: {}. It will be ignored", tlv);
            }
        }
        return tlvMapBuilder.build();
    }

    public void configMessageHashPrefix() {
        //the message hash prefix for dlr uniqueness is: type + host + port + user
        //this allows to have multiple clients (multiple instances of the worker, not multiple connections within the worker)
        //connecting to the same SMSC (host/port/user) and dealing with the received DLRs (which the host might send to either instance)
        String prefix = configurationProvider.getPrpt(_msgHashPrefix);
        if (Strings.isNullOrEmpty(prefix)) {
            this.messageHashPrefix = getInstanceName();
            logger.debug("No message hash prefix has been specified. Auto-created one using instance-name:{}",
                    this.messageHashPrefix);
        } else {
            this.messageHashPrefix = prefix;
            logger.debug("configured message hash prefix as: {}", prefix);
        }
    }

    public void configDlrsErrorCodeMapping() {
        String errMappingsCsv = configurationProvider.getPrpt(_dlrErrCodes);
        if (Strings.isNullOrEmpty(errMappingsCsv)) {
            dlrErrCodeMap = ImmutableMap.of();
            return;
        }
        try {
            Map<String, String> errs = new HashMap<>();
            String[] errMappings = errMappingsCsv.split(",");
            for (String errMapping : errMappings) {
                String[] errCodes = errMapping.split("_");
                errs.put(errCodes[0], errCodes[1]);
            }
            dlrErrCodeMap = ImmutableMap.copyOf(errs);
            logger.info("configured dlrs error code mapping: {}", dlrErrCodeMap);
        } catch (Exception e) {
            logger.warn("error configuring dlrs error code mappings from value: {}", errMappingsCsv, e);
        }
    }

    public void configRespErrorCodeMapping() {
        String errMappingsCsv = configurationProvider.getPrpt(_respErrCodes);
        if (Strings.isNullOrEmpty(errMappingsCsv)) {
            respErrCodeMap = ImmutableMap.of();
            return;
        }
        try {
            Map<String, String> errs = new HashMap<>();
            String[] errMappings = errMappingsCsv.split(",");
            for (String errMapping : errMappings) {
                String[] errCodes = errMapping.split("_");
                errs.put(errCodes[0], errCodes[1]);
            }
            respErrCodeMap = ImmutableMap.copyOf(errs);
            logger.info("configured resp error code mapping: {}", respErrCodeMap);
        } catch (Exception e) {
            logger.warn("error configuring resp error code mappings from value: {}", errMappingsCsv, e);
        }
    }

    protected M handleMessageDuringSuspension(M msg) {
        return switch (suspensionPolicy) {
            case RETRY_ROUTER -> {
                onMessageFailed(msg);
                yield null;
            }
            case FAIL -> {
                failMessage(SmppConstants.STATUS_BINDFAIL, null, msg);
                yield null;
            }
            case SUSPEND -> {
                logger.warn("UNEXPECTED message handling during suspension:{}: {}", suspensionPolicy, msg);
                yield msg;
            }
        };
    }

    public List<SubmitSm> generateSubmitRequest(M pMsg) throws SmppInvalidArgumentException {
        List<SubmitSm> requests = new ArrayList<>();
        byte dataCoding = getDcsForMessageType(pMsg.type);
        String charset = getCharsetForDcs(dataCoding);
        byte[][] ccatBodies;
        if (CHARSET_HEX.equals(charset)) {
            ccatBodies = new byte[1][];
            ccatBodies[0] = HexUtil.toByteArray(Strings.nullToEmpty(pMsg.binheader).concat(pMsg.body));
        } else {
            if (Strings.isNullOrEmpty(pMsg.binheader)) {
                boolean ccat8Bit = configurationProvider.getBlnPrpt(_ccat8bit);
                int msgRefNum = generateMessageReferenceNumber(ccat8Bit);
                ccatBodies = SmppServerUtil.splitMessage(pMsg.body, charset, msgRefNum, ccat8Bit);
            } else {
                byte[] udh = HexUtil.toByteArray(pMsg.binheader);
                byte[] sm = CharsetUtil.encode(pMsg.body, charset);
                ccatBodies = new byte[1][];
                ccatBodies[0] = SmppServerUtil.mergeByteArrays(udh, sm);
            }
        }

        Address srcAddr = getSourceAddress(pMsg.from);
        String msgServiceType = serviceType == null ? null : serviceType.getValueFor(pMsg);
        byte msgPriority = 0;
        if (priority != null) {
            String msgPriorityStr = priority.getValueFor(pMsg);
            if (msgPriorityStr != null) {
                try {
                    msgPriority = Byte.parseByte(msgPriorityStr);
                } catch (Exception e) {
                    logger.warn("exception parsing priority value: {}. Reverting to default 0", msgPriorityStr);
                }
            }
        }

        for (byte[] shortText : ccatBodies) {
            SubmitSm submit = new SubmitSm();

            submit.setSourceAddress(srcAddr);
            submit.setDestAddress(new Address(destAddrTon, destAddrNpi, pMsg.to));

            submit.setRegisteredDelivery(pMsg.acked ?
                    SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED :
                    SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_NOT_REQUESTED);
            //if we have concatenated sms, or if we had udh
            if (!esmClassOverride && (ccatBodies.length > 1 || (pMsg.binheader != null && !pMsg.binheader.isEmpty()))) {
                submit.setEsmClass(SmppConstants.ESM_CLASS_UDHI_MASK);
            }
            submit.setEsmClass((byte) (submit.getEsmClass() | esmClass));

            if (msgServiceType != null) {
                submit.setServiceType(msgServiceType);
            }
            submit.setDataCoding(dataCoding);
            submit.setShortMessage(shortText);
            if (pMsg.tlvs != null && !pMsg.tlvs.isEmpty() && !this.tlvsSubmit.isEmpty()) {
                pMsg.tlvs.forEach((key, value) -> {
                    Short tag = tlvsSubmit.get(key);
                    if (tag != null) {
                        submit.addOptionalParameter(new Tlv(tag, value.getBytes(StandardCharsets.UTF_8), key));
                    }
                });
            }

            if (priority != null) {
                submit.setPriority(msgPriority);
            }

            /*
            //the following fields are not being set
            submit.setScheduleDeliveryTime();
            submit.setProtocolId();
            submit.setReplaceIfPresent();
            submit.setDefaultMsgId();
            submit.setValidityPeriod();
            */
            pMsg.smsSubmitCnt = ccatBodies.length;
            if (ccatBodies.length == 1 || requests.isEmpty()) {
                submit.setReferenceObject(pMsg);
            } else {
                submit.setReferenceObject(generateMessageReferenceForSubmitSm(pMsg, submit, charset));
            }

            requests.add(submit);
        }

        return requests;
    }

    public PduResponse parseDlrAndCreateResponse(DeliverSm deliverSm) {
        try {
            final var from = deliverSm.getSourceAddress().getAddress();
            final var to = deliverSm.getDestAddress().getAddress();

            String charset;
            if (fixedDlrCharset != null) {
                charset = fixedDlrCharset;
            } else {
                charset = getCharsetForDcs(deliverSm.getDataCoding());
            }
            var dlrBody = CHARSET_HEX.equals(charset) ? new String(deliverSm.getShortMessage()) :
                    CharsetUtil.decode(deliverSm.getShortMessage(), charset);
            if (!Strings.isNullOrEmpty(dlrBody) && !charset.equals(CharsetUtil.NAME_GSM) && !dlrBody.startsWith("id:")) {
                // attempt to re-parse the dlr with gsm charset and auto-fix
                try {
                    var gsmBody = CharsetUtil.decode(deliverSm.getShortMessage(), CharsetUtil.NAME_GSM);
                    if (!Strings.isNullOrEmpty(gsmBody) && gsmBody.startsWith("id:")) {
                        dlrBody = gsmBody;
                    }
                } catch (Exception e) {
                    logger.warn("error trying to re-parse dlr with gsm charset: {}", deliverSm, e);
                }
            }
            if (Strings.isNullOrEmpty(dlrBody)) {
                logger.debug("failed to decode dlr body with charset: {}. Decoding it as-is with default charset",
                        charset);
                dlrBody = new String(deliverSm.getShortMessage());
            }
            // the original parseShortMessage method will throw an exception if err field is more than 3 chars
            // among other validations it performs. The extended one does not throw exception for invalid fields
            var receipt = DeliveryReceipt.parseShortMessage(dlrBody, ZoneOffset.UTC, false, false);
            String errcode = extractErrorCode(receipt.getRawErrorCode(), receipt.getErrorCode());
            int state = SmppServerUtil.decodeFinalState(receipt.getState());
            if (dlrBody.length() > 159) {
                dlrBody = dlrBody.substring(0, 159);
            }
            String smscid = decodeMessageID(true, receipt.getMessageId());
            if (Strings.isNullOrEmpty(smscid)) {
                logger.warn("Invalid smscid: null or empty, skipping unknown dlr: {}", deliverSm);
                return deliverSm.createGenericNack(SmppConstants.STATUS_SYSERR);
            }
            HashMap<String, String> tlvs = extractTlvs(this.tlvsDlrs, deliverSm);
            messageTracker.createAndEnqueueDLR(0, smscid, getHashedMessageID(smscid), from, to, dlrBody, state, errcode, tlvs);
        } catch (Exception e) {
            //our own extended delivery receipt parsing method will not throw exception for dlr field validation
            //so this means that something else went really wrong
            logger.warn("caught exception while parsing dlr: {}", deliverSm, e);
            PduResponse resp = deliverSm.createResponse();
            resp.setCommandStatus(SmppConstants.STATUS_SYSERR);
            return resp;
        }

        return deliverSm.createResponse();
    }

    protected String extractErrorCode(String rawErrcode, int intErrCode) {
        String mappedErrorCode = rawErrcode;
        int parsedErrorCode = intErrCode;
        if (dlrErrCodeMap != null && !dlrErrCodeMap.isEmpty()) {
            String errcode = dlrErrCodeMap.get(rawErrcode);
            if (Strings.isNullOrEmpty(errcode)) {
                errcode = dlrErrCodeMap.get(Integer.toString(intErrCode));
            }
            if (!Strings.isNullOrEmpty(errcode)) {
                try {
                    mappedErrorCode = errcode;
                    parsedErrorCode = Integer.parseInt(errcode);
                } catch (Exception ignored) {
                }
            }
        }

        try {
            return Integer.toString(SmppServerUtil.encodeErrorCode(parsedErrorCode));
        } catch (Exception e) {
            return mappedErrorCode;
        }
    }

    public PduResponse parseMoAndCreateResponse(BaseSm request) {
        final StandardMessage mo = new StandardMessage();
        byte[] data = request.getShortMessage();
        if (data == null || data.length == 0) {
            var messagePayloadTlv = request.getOptionalParameter(SmppConstants.TAG_MESSAGE_PAYLOAD);
            if (messagePayloadTlv != null) {
                data = messagePayloadTlv.getValue();
            }
        }
        String udh = null;
        if (SmppUtil.isUserDataHeaderIndicatorEnabled(request.getEsmClass())) {
            int udhLength = data[0];
            byte[] udhBytes = new byte[udhLength + 1];
            System.arraycopy(data, 0, udhBytes, 0, udhBytes.length);
            udh = HexUtil.toHexString(udhBytes);
            byte[] bodyBytes = new byte[data.length - udhBytes.length];
            System.arraycopy(data, udhBytes.length, bodyBytes, 0, bodyBytes.length);

            data = bodyBytes;
        }

        final byte requestDataCoding = request.getDataCoding();
        final String charset = getCharsetForDcs(requestDataCoding);
        final int smtype = getMessageTypeFromDcs(requestDataCoding);
        String text;
        if (CHARSET_HEX.equals(charset)) {
            text = HexUtil.toHexString(data);
        } else {
            text = CharsetUtil.decode(data, charset);
        }

        mo.tlvs = extractTlvs(this.tlvsMos, request);
        if (logger.isDebugEnabled() && this.debug && mo.tlvs != null) {
            logger.debug("added tlvs:{} to mo:{}", mo.tlvs, mo);
        }

        //todo: what benefit can possibly come from truncating the received message?
        //text = SMS_Validation.validateText(udh, text == null ? "" : text, smtype, max_length);
        text = text.replace("\0", "");

        mo.from = request.getSourceAddress().getAddress();
        mo.to = request.getDestAddress().getAddress();
        mo.body = text;
        mo.binheader = udh;
        mo.type = smtype;
        mo.acked = request.getRegisteredDelivery() != SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_NOT_REQUESTED;
        mo.timestamp = String.valueOf(new java.sql.Timestamp(System.currentTimeMillis()));
        if (getMessageTracker().getConfiguredMccMnc() > 0) {
            mo.cnetwork = getMessageTracker().getConfiguredMccMnc();
        }

        String moForwardUrl = configurationProvider.getPrpt(_forwardMoUrl);
        if (moForwardUrl != null && !moForwardUrl.isEmpty()) {
            ForwardMoService.ForwardFormat format;
            try {
                format = ForwardMoService.ForwardFormat.valueOf(
                        configurationProvider.getPrpt(_forwardMoFormat).toUpperCase());
            } catch (Exception e) {
                format = ForwardMoService.ForwardFormat.JSON;
            }
            workerResources.getForwardMoService().forwardMo(moForwardUrl,
                    new ForwardMoService.MoContext(mo.from, mo.to, mo.body, mo.timestamp,
                            mo.ingateway, mo.message_center, request.getDataCoding()),
                    format);
        }

        return request.createResponse();
    }

    protected void routeMo(M msg) {
        msg.message_center = getInstanceName();
        msg.ingateway = getFullName();
        if (printMos) {
            logger.info("enqueuing mo: {}", msg);
        }

        while (true) {
            try {
                enqueueToRouter(msg);
                return;
            } catch (InterruptedException e) {
                TimeUtils.sleep(10, TimeUnit.MILLISECONDS);
            }
        }
    }

    public void handleResponse(SmppClientSessionHandler handler, int statusCode, String respMessageId, M msg) {
        if (printResps) {
            logger.info("Received response:{}-{} with smscid:{} for msg:{}",
                    statusCode, handler.lookupResultMessage(statusCode), respMessageId, msg);
        }

        if (statusCode == SmppConstants.STATUS_OK) {
            successMessage(respMessageId, msg);
            return;
        }

        NackHandlePolicy policy = findPolicyForStatusCode(statusCode);

        switch (policy) {
            case RETRY_WORKER:
                onMessageTemporaryFailed(msg);
                break;
            case RETRY_ROUTER:
                if (retryRouterRemoveHlr) {
                    if (msg.cnetwork > 0) {
                        msg.cnetwork = 0;
                        msg.outgateway = "";
                    }
                }
                onMessageFailed(msg);
                break;
            case FAIL:
            default:
                failMessage(statusCode, respMessageId, msg);
                break;
        }
    }

    public NackHandlePolicy findPolicyForStatusCode(int statusCode) {
        NackHandlePolicy policy;
        if (isRetryWorkerStatusCode(statusCode)) {
            policy = NackHandlePolicy.RETRY_WORKER;
        } else if (isRetryRouterStatusCode(statusCode)) {
            policy = NackHandlePolicy.RETRY_ROUTER;
        } else if (isFailStatusCode(statusCode)) {
            policy = NackHandlePolicy.FAIL;
        } else {
            policy = defaultNackHandlePolicy;
        }
        return policy;
    }

    protected void successMessage(String respMessageId, M msg) {
        updateSendStatusAndSmscId(respMessageId, msg);
        try {
            onMessageSuccess(msg);
        } catch (Exception e) {
            logger.warn("exception on message success callback for msg:{}", msg, e);
        }
    }

    public void failMessage(int commandStatus, String respMessageId, M msg) {
        failedMsgCounter.remove(msg.msgId);
        if (msg.msgId < 0) {
            return;
        }

        String smscid = updateSendStatusAndSmscId(respMessageId, msg);
        String smsid = getHashedMessageID(smscid);

        String errorCode;
        if (respErrCodeMap != null && !respErrCodeMap.isEmpty()) {
            errorCode = respErrCodeMap.getOrDefault(String.valueOf(commandStatus),
                    String.valueOf(StandardMessage.DLR_ERR_SMS_FAILED));
        } else {
            errorCode = String.valueOf(StandardMessage.DLR_ERR_SMS_FAILED);
        }

        messageTracker.createAndEnqueueDLR(msg.msgId, smscid, smsid, msg.from, msg.to, "" + commandStatus,
                StandardMessage.DLR_STAT_FAILED, errorCode, null);
    }

    public String updateSendStatusAndSmscId(String respMessageId, M msg) {
        if (msg.msgId < 0) {
            return null;
        }
        //message was sent, we need to record the mapping between smsid and mqid
        final String smscid;
        if (Strings.isNullOrEmpty(respMessageId)) {
            smscid = getInternalSmscId(msg.msgId);
        } else {
            smscid = decodeMessageID(false, respMessageId);
        }
        final String hashedMessageID = getHashedMessageID(smscid);
        int size = getThreadCount();

        updateSendStatusAndExtID(hashedMessageID, msg, smscid);
        return smscid;
    }

    public String getInternalSmscId(int msgId) {
        return getFullName() + "_internal_" + msgId;
    }

    protected StandardMessage generateMessageReferenceForSubmitSm(M original, SubmitSm submitSm, String charset) {
        M msg;
        try {
            msg = (M) original.clone();
        } catch (CloneNotSupportedException e) {
            logger.warn("unable to clone original message. using the original");
            return original;
        }
        boolean ccat8Bit = configurationProvider.getBlnPrpt(_ccat8bit);
        byte[] sm = submitSm.getShortMessage();
        byte[] udh = new byte[ccat8Bit ? 6 : 7];
        System.arraycopy(sm, 0, udh, 0, udh.length);
        msg.binheader = HexUtil.toHexString(udh);
        byte[] body = new byte[sm.length - udh.length];
        System.arraycopy(sm, udh.length, body, 0, body.length);
        msg.body = CharsetUtil.decode(body, charset);
        msg.ddt = msg.msgId;
        msg.msgId = -1;

        return msg;
    }

    public int generateMessageReferenceNumber(boolean ccat8bit) {
        int newVal = msgRefNumGenerator.incrementAndGet();
        return ccat8bit ? (byte) newVal : (short) newVal;
    }

    public String decodeMessageID(boolean dlr, String messageId) {
        String decoded;
        if (MsgIdType.StringLiteral.equals(msgIdType)) {
            decoded = messageId;
        } else if ((!dlr && MsgIdType.SubmitRespDecDlrHex.equals(msgIdType)) ||
                (dlr && MsgIdType.SubmitRespHexDlrDec.equals(msgIdType))) {
            try {
                decoded = new BigInteger(messageId).toString();
            } catch (Exception e) {
                logger.warn("Exception trying to parse id: {} as dec. Skipping decoding", messageId);
                decoded = messageId;
            }
        } else {
            try {
                decoded = new BigInteger(messageId, 16).toString();
            } catch (Exception e) {
                logger.warn("Exception trying to parse id: {} as hex. Skipping decoding", messageId);
                decoded = messageId;
            }
        }
        logger.debug("Decoded SMSC ID from:{} to:{}", messageId, decoded);
        return decoded;
    }

    public Address getSourceAddress(String from) {
        byte srcTon = srcAddrTon;
        byte srcNpi = srcAddrNpi;
        if (srcAddrAutodetect) {
            if (!StringUtil.containsOnlyDigits(from)) {
                srcTon = SmppConstants.TON_ALPHANUMERIC;
                srcNpi = SmppConstants.NPI_UNKNOWN;
            }
        }

        return new Address(srcTon, srcNpi, from);
    }

    public MessageFlexValue getServiceType() {
        return serviceType;
    }

    public byte getEsmClass() {
        return esmClass;
    }

    public void setEsmClass(byte esmClass) {
        this.esmClass = esmClass;
    }

    public boolean isSrcAddrAutodetect() {
        return srcAddrAutodetect;
    }

    public byte getSrcAddrTon() {
        return srcAddrTon;
    }

    public byte getSrcAddrNpi() {
        return srcAddrNpi;
    }

    public byte getDestAddrTon() {
        return destAddrTon;
    }

    public byte getDestAddrNpi() {
        return destAddrNpi;
    }

    public SuspensionPolicy getSuspensionPolicy() {
        return suspensionPolicy;
    }

    public ImmutableSet<Integer> getRetryRouterStatusCodes() {
        return retryRouterStatusCodes;
    }

    public boolean isRetryRouterStatusCode(int statusCode) {
        return retryRouterStatusCodes.contains(statusCode);
    }

    public ImmutableSet<Integer> getRetryWorkerStatusCodes() {
        return retryWorkerStatusCodes;
    }

    public boolean isRetryWorkerStatusCode(int statusCode) {
        return retryWorkerStatusCodes.contains(statusCode);
    }

    public ImmutableSet<Integer> getFailStatusCodes() {
        return failStatusCodes;
    }

    public boolean isFailStatusCode(int statusCode) {
        return failStatusCodes.contains(statusCode);
    }

    public NackHandlePolicy getDefaultNackHandlePolicy() {
        return defaultNackHandlePolicy;
    }

    public int getMaxConsecutiveFailedEnquireLinksBeforeReconnecting() {
        return configurationProvider.getIntPrpt(_enquireLinkErrors);
    }

    public ImmutableMap<String, Short> getTlvsSubmit() {
        return tlvsSubmit;
    }

    public ImmutableMap<Short, String> getTlvsMos() {
        return tlvsMos;
    }

    public ImmutableMap<Short, String> getTlvsDlrs() {
        return tlvsDlrs;
    }

    public String getHost() {
        return configurationProvider.getPrpt(_host);
    }

    public int getPort() {
        return configurationProvider.getIntPrpt(_port);
    }

    public int getWindowSize() {
        return configurationProvider.getIntPrpt(_windowSize);
    }

    public String getUsername() {
        return configurationProvider.getPrpt(_user);
    }

    public SmppConnectionManager<SmppClientSessionHandler> getSessionHandlers() {
        return sessionHandlers;
    }

    public SmppClientSessionHandler getAvailableHandlerForSending() {
        return sessionHandlers.getAvailableHandlerForSending();
    }

    public void setEsmClassOverride(boolean esmClassOverride) {
        this.esmClassOverride = esmClassOverride;
    }

    public ScheduledExecutorService getSuspensionMonitorExecutor() {
        return suspensionMonitorExecutor;
    }

    public Set<Integer> getExcludeLogPdus() {
        var exclude = configurationProvider.getPrpt(_logPdusExclude);
        if (Strings.isNullOrEmpty(exclude)) {
            return null;
        }
        var cmdIds = exclude.split(",");
        return Arrays.stream(cmdIds).map(Ints::tryParse).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    public void scheduleConnectionsCheck() {
        //cancel any previous check connections task, so that we don't have
        //multiple ones scheduled
        if (checkConnectionsFuture != null) {
            checkConnectionsFuture.cancel(true);
            checkConnectionsFuture = null;
        }

        //set up check connections task
        checkConnectionsFuture = reconnectionExecutor.scheduleWithFixedDelay(
                this::checkConnections,
                0,
                configurationProvider.getLongPrpt(_reconnectInterval),
                TimeUnit.MILLISECONDS);
    }

    public void schedulePeriodicEnquireLinks() {
        //cancel any previous enquire links task, so that we don't have
        //multiple ones scheduled
        if (enquireLinksFuture != null) {
            enquireLinksFuture.cancel(true);
            enquireLinksFuture = null;
        }

        //set up enquire links task
        long enquireLinksIntervalMs = configurationProvider.getLongPrpt(_enquireLinkInterval);
        if (enquireLinksIntervalMs <= 0) {
            logger.info("disabling enquire links, due to negative/zero interval: {}", enquireLinksIntervalMs);
            return;
        }

        enquireLinksFuture = reconnectionExecutor.scheduleWithFixedDelay(
                this::enquireLinks, enquireLinksIntervalMs, enquireLinksIntervalMs, TimeUnit.MILLISECONDS);
    }

    public boolean addConnection(ConnectionInfo connectionInfo) {
        SmppSessionConfiguration configuration = new SmppSessionConfiguration();
        configuration.setWindowSize(configurationProvider.getIntPrpt(_windowSize));
        configuration.setName(getFullName() + "." + connectionInfo.bindType.name() + ".ThreadSession." + (sessionHandlers.size() + 1));
        configuration.setType(connectionInfo.bindType);
        configuration.setHost(connectionInfo.host);
        configuration.setPort(connectionInfo.port);
        configuration.setConnectTimeout(configurationProvider.getLongPrpt(_conTout));
        configuration.setBindTimeout(configurationProvider.getLongPrpt(_requestTout));
        configuration.setSystemId(configurationProvider.getPrpt(_user));
        configuration.setPassword(configurationProvider.getPrpt(_pass));
        configuration.getLoggingOptions().setLogBytes(configurationProvider.getBlnPrpt(_logBytes));
        configuration.getLoggingOptions().setLogPdu(configurationProvider.getBlnPrpt(_logPdus));
        configuration.getLoggingOptions().setExcludeLogPdus(getExcludeLogPdus());
        String packageName = SmppClientWorker.class.getPackageName();
        configuration.getLoggingOptions().setLoggerName(packageName + "." + getInstanceName());
        //enable monitoring (request expiration)
        configuration.setRequestExpiryTimeout(configurationProvider.getLongPrpt(_requestTout));
        configuration.setWindowMonitorInterval(configurationProvider.getLongPrpt(_windowMonitorInterval));
        configuration.setCountersEnabled(configurationProvider.getBlnPrpt(_counters));
        configuration.setSystemType(configurationProvider.getPrpt(_systemType));
        String clientBindHost = configurationProvider.getPrpt(_localBindHost);
        if (!Strings.isNullOrEmpty(clientBindHost)) {
            configuration.setClientBindHost(clientBindHost);
        }
        String iv = configurationProvider.getPrpt(_interfaceVersion);

        if (configurationProvider.getBlnPrpt(_ssl)) {
            // create a SSL configuration
            SslConfiguration sslConfig = new SslConfiguration();
            // Which trusts all certs by default. You can turn this off with
            sslConfig.setTrustAll(configurationProvider.getBlnPrpt(_sslTrustAll));

            // sslConfig.setValidateCerts(false);
            // sslConfig.setValidatePeerCerts(false);

            // And add it to the configuration:
            configuration.setSslConfiguration(sslConfig);
        }

        if (iv != null && !iv.isEmpty()) {
            byte interfaceVersion;
            try {
                interfaceVersion = Byte.parseByte(iv);
            } catch (Exception e) {
                logger.warn("Could not parse interface version: {}. Reverting to default 3.4", iv);
                interfaceVersion = SmppConstants.VERSION_3_4;
            }
            configuration.setInterfaceVersion(interfaceVersion);
        }
        try {
            var addrRange = new Address();
            if (!Strings.isNullOrEmpty(configurationProvider.getPrpt(_addressRangeTon))) {
                addrRange.setTon(Byte.parseByte(configurationProvider.getPrpt(_addressRangeTon)));
            }
            if (!Strings.isNullOrEmpty(configurationProvider.getPrpt(_addressRangeNpi))) {
                addrRange.setNpi(Byte.parseByte(configurationProvider.getPrpt(_addressRangeNpi)));
            }
            if (!Strings.isNullOrEmpty(configurationProvider.getPrpt(_addressRange))) {
                addrRange.setAddress(configurationProvider.getPrpt(_addressRange));
            }
            configuration.setAddressRange(addrRange);
        } catch (NumberFormatException e) {
            //case where the TON or NPI value is not a valid byte
            logger.error("Invalid byte value in address range TON/NPI configuration: {}", e.getMessage());
        }
        try {
            final var handler = new SmppClientSessionHandler(this, connectionInfo);
            final var client = smppClient;
            final var session = client.bind(configuration, handler);
            if (session == null || !session.isBound()) {
                if (session != null) {
                    try {
                        session.destroy();
                    } catch (Exception e) {
                        logger.warn("received not bound session and failed to destroy it", e);
                    }
                }
                throw new Exception("null session returned from bind");
            } else {
                logger.info("Established connection: [{}]", connectionInfo);
            }
            handler.setSession(session);

            return sessionHandlers.add(handler);

        } catch (Exception e) {
            logger.warn("failed to add connection [{}] due to error: {}", connectionInfo, e.getMessage());
        }

        return false;
    }

    public boolean removeConnection(SmppClientSessionHandler handler, boolean unbind) {
        return removeConnection(handler, unbind, false);
    }

    public boolean removeConnection(SmppClientSessionHandler handler, boolean unbind, boolean checkConnectivity) {
        if (handler == null) {
            return false;
        }

        sessionHandlers.remove(handler);

        if (handler.getSession() != null) {
            if (unbind) {
                try {
                    handler.getSession().unbind(configurationProvider.getIntPrpt(_unbindTimeout));
                } catch (Exception e) {
                    logger.warn("Exception while unbinding. Moving on to destroying the session");
                }
            }
            handler.getSession().destroy();
        }

        boolean shouldRetryImmediately = checkConnectivity;
        if (checkConnectivity) {
            long lifespan = System.currentTimeMillis() - handler.getSessionStartTime();
            long threshold = configurationProvider.getIntPrpt(_reconnectionStabilityThreshold);
            // if threshold is 0 skip the interval mechanism and retry immediately
            if (threshold > 0 && lifespan < threshold) {
                if (debug) {
                    logger.info("{}: Connection lasted only {} ms. Unstable connection detected. " +
                            "Skipping immediate retry to honor reconnect interval.", getFullName(), lifespan);
                }
                shouldRetryImmediately = false;
            }
        }

        if (shouldRetryImmediately && !verifyConnectivity() && !suspendAuto) {
            reconnectionExecutor.submit(this::checkConnections);
        }

        return true;
    }

    public boolean verifyConnectivity() {
        for (var handler : sessionHandlers.getAllHandlers()) {
            if (handler.isSessionBound()) {
                var bt = handler.getSession().getBindType();
                if (bt == SmppBindType.TRANSCEIVER || bt == SmppBindType.TRANSMITTER) {
                    return true;
                }
            }
        }

        return false;
    }

    public ConnectionConfiguration getConnectionsConfiguration() {
        var config = new ConnectionConfiguration();
        int transceivers = configurationProvider.getIntPrpt(_transceivers);
        int transmitters = configurationProvider.getIntPrpt(_transmitters);
        int receivers = configurationProvider.getIntPrpt(_receivers);

        String normalHost = configurationProvider.getPrpt(_host);
        int normalPort = configurationProvider.getIntPrpt(_port);
        var extraHosts = parseConnectionValue(configurationProvider.getPrpt(_extraHosts));
        var backupHosts = parseConnectionValue(configurationProvider.getPrpt(_backupHosts));

        if (transceivers > 0) {
            config.increaseCounter(new ConnectionInfo(SmppBindType.TRANSCEIVER, normalHost, normalPort, ConnectionType.NORMAL), transceivers);
            for (var extra : extraHosts) {
                config.increaseCounter(new ConnectionInfo(SmppBindType.TRANSCEIVER, extra.host, extra.port, ConnectionType.EXTRA), transceivers);
            }
            for (var backup : backupHosts) {
                config.increaseCounter(new ConnectionInfo(SmppBindType.TRANSCEIVER, backup.host, backup.port, ConnectionType.BACKUP), transceivers);
            }
        }
        if (transmitters > 0) {
            config.increaseCounter(new ConnectionInfo(SmppBindType.TRANSMITTER, normalHost, normalPort, ConnectionType.NORMAL), transmitters);
            for (var extra : extraHosts) {
                config.increaseCounter(new ConnectionInfo(SmppBindType.TRANSMITTER, extra.host, extra.port, ConnectionType.EXTRA), transmitters);
            }
            for (var backup : backupHosts) {
                config.increaseCounter(new ConnectionInfo(SmppBindType.TRANSMITTER, backup.host, backup.port, ConnectionType.BACKUP), transmitters);
            }
        }
        if (receivers > 0) {
            config.increaseCounter(new ConnectionInfo(SmppBindType.RECEIVER, normalHost, normalPort, ConnectionType.NORMAL), receivers);
            for (var extra : extraHosts) {
                config.increaseCounter(new ConnectionInfo(SmppBindType.RECEIVER, extra.host, extra.port, ConnectionType.EXTRA), receivers);
            }
            for (var backup : backupHosts) {
                config.increaseCounter(new ConnectionInfo(SmppBindType.RECEIVER, backup.host, backup.port, ConnectionType.BACKUP), receivers);
            }
        }

        return config;
    }

    protected void removeInvalidConnections() {
        for (var handler : sessionHandlers.getAllHandlers()) {
            if (handler.session.isUnbinding() || handler.session.isClosed()) {
                logger.debug("found closed/unbinding session, removing it");
                removeConnection(handler, false);
            }
        }
    }

    protected ConnectionConfiguration getCurrentConnections() {
        ConnectionConfiguration current = new ConnectionConfiguration();
        for (var handler : sessionHandlers.getAllHandlers()) {
            current.increaseCounter(handler.info);
        }
        return current;
    }

    protected void addRemoveConnections(ConnectionConfiguration currentConnections, ConnectionConfiguration configuredConnections) {
        //try to add any missing connection types (from current to config)
        for (var handler : sessionHandlers.getAllHandlers()) {
            if (currentConnections.get(handler.info) > configuredConnections.get(handler.info)) {
                removeConnection(handler, true);
                currentConnections.decreaseCounter(handler.info);
            }
        }

        for (var connectionInfo : configuredConnections.connections.keySet()) {
            int config = configuredConnections.get(connectionInfo);
            int current = currentConnections.get(connectionInfo);

            for (int i = current; i < config; i++) {
                boolean added = addConnection(connectionInfo);
                if (added) {
                    currentConnections.increaseCounter(connectionInfo);
                }
            }
        }
    }

    protected List<ConnectionHost> parseConnectionValue(String connval) {
        if (Strings.isNullOrEmpty(connval)) {
            return Collections.emptyList();
        }

        List<ConnectionHost> result = new ArrayList<>();
        try {
            String[] hosts = connval.split(",");
            for (String hostPort : hosts) {
                String[] hp = hostPort.split(":");
                result.add(new ConnectionHost(hp[0], Integer.parseInt(hp[1])));
            }
        } catch (Exception e) {
            logger.warn("error parsing connection host/port configuration: {}", connval);
        }

        return result;
    }

    public final void checkConnections() {
        try {
            //remove closed/unbinding connections
            removeInvalidConnections();

            //verify that the configuration specifies at least 1 transmitter and 1 receiver
            var config = getConnectionsConfiguration();
            if (config.hasNoTrasmitters()) {
                long reconnectInterval = configurationProvider.getLongPrpt(_reconnectInterval);
                logger.warn("PAUSING: Invalid connection types specified in configuration, missing at least 1 transceiver or 1 transmitter! " +
                        "Current configuration values are: {}. Rescheduling checks for connections in {} millis.", config, reconnectInterval);
                //set to suspended, until we've established the connections
                initiateAutoSuspension();
                if (!isKeepOnRunning()) {
                    return;
                }
            }

            //check sessionHandlers and count current types
            var current = getCurrentConnections();
            final boolean previousSuspendAuto = suspendAuto;
            if (!suspendAuto && current.hasNoTrasmitters()) {
                suspendAuto = true;
            }

            //add/remove connections to match config
            addRemoveConnections(current, config);

            //let's check if the connections we added got us connectivity
            if (!verifyConnectivity()) {
                if (!previousSuspendAuto) {
                    long reconnectInterval = configurationProvider.getLongPrpt(_reconnectInterval);
                    logger.warn("PAUSING: current connections:{} PAUSING until there are at least 1 transceiver or 1 transmitter connection. " +
                            "Rescheduling checks for connections in {} millis", current, reconnectInterval);
                    //set to suspended, until we've established the connections
                    initiateAutoSuspension();
                }
                return;
            }

            //we've established enough connections to be up and running
            //check if we've automatically got to suspend
            if (suspendAuto) {
                logger.info("UNPAUSING: reverting automatic pause, since we've established required connections");
                endAutoSuspension();
            }
        } catch (Exception e) {
            logger.warn("exception caught while checking connections", e);
        }
    }

    public void enquireLinks() {
        boolean onlyWhenNoOtherTraffic = configurationProvider.getBlnPrpt(_enquireLinkNoTrafficOnly);
        long prevEnquireRun = System.currentTimeMillis() - configurationProvider.getLongPrpt(_enquireLinkInterval);
        for (SmppClientSessionHandler handler : sessionHandlers.getAllHandlers()) {
            if (Thread.interrupted()) {
                logger.debug("enquire links runnable was interrupted, returning");
                return;
            }

            try {
                long handlerLastPduTstamp = handler.getLastPduTimestamp();
                if (handler.session.isBound() &&
                        (handlerLastPduTstamp <= 0 || !onlyWhenNoOtherTraffic || handlerLastPduTstamp < prevEnquireRun)) {
                    handler.session.sendRequestPdu(new EnquireLink(), configurationProvider.getLongPrpt(_requestTout), false);
                }
            } catch (InterruptedException e) {
                logger.debug("enquire links runnable was interrupted, returning");
                return;
            } catch (Exception e) {
                logger.warn("exception while trying to send enquire link", e);
            }
        }
    }

    public static Map<Byte, String> parseCodingCharsetString(String csv) {
        Map<Byte, String> map = new HashMap<>();
        if (Strings.isNullOrEmpty(csv)) {
            return map;
        }
        String[] mappings = csv.split(",");
        if (mappings.length == 0) {
            return map;
        }
        for (String mapping : mappings) {
            String[] dcsCharset = mapping.split("_");
            map.put((byte) Integer.parseInt(dcsCharset[0]), CHARSET_HEX.equals(dcsCharset[1]) ? CHARSET_HEX : getCharset(dcsCharset[1], CharsetUtil.NAME_GSM));
        }
        return map;
    }

    public static String getCharset(String name, String def) {
        try {
            String charsetName = name.toUpperCase();
            Charset cs = CharsetUtil.map(charsetName);
            if (cs == null) {
                cs = new CustomCharset(charsetName);
                CharsetUtil.charsets.put(charsetName, cs);
            }
            return charsetName;
        } catch (Exception e) {
            String defUpper = def.toUpperCase();
            LoggerFactory.getLogger(SmppClientWorker.class).warn("exception trying to find charset {}. instead using default:{}", name, defUpper);
            return defUpper;
        }
    }

    public static HashMap<String, String> extractTlvs(Map<Short, String> registeredTlvs, BaseSm request) {
        ArrayList<Tlv> tlvs = request.getOptionalParameters();
        if (tlvs == null || registeredTlvs.isEmpty() || tlvs.isEmpty()) {
            return null;
        }

        HashMap<String, String> extracted = new HashMap<>();
        for (Tlv tlv : tlvs) {
            String tag = registeredTlvs.get(tlv.getTag());
            if (!Strings.isNullOrEmpty(tag)) {
                extracted.put(tag, new String(tlv.getValue(), StandardCharsets.UTF_8));
            }
        }

        return extracted.isEmpty() ? null : extracted;
    }

    public enum MsgIdType {
        StringLiteral,
        SubmitRespHexDlrDec,
        SubmitRespDecDlrHex
    }

    public enum ConnectionType {
        NORMAL, EXTRA, BACKUP
    }

    public enum NackHandlePolicy {
        RETRY_ROUTER, RETRY_WORKER, FAIL
    }

    public record ConnectionHost(String host, int port) {}

    public record ConnectionInfo(SmppBindType bindType, String host, int port, ConnectionType type) {}

    public static class ConnectionConfiguration {
        public final Map<ConnectionInfo, AtomicInteger> connections;

        public ConnectionConfiguration() {
            this.connections = new HashMap<>();
        }

        public int get(ConnectionInfo info) {
            return this.connections.computeIfAbsent(info, k -> new AtomicInteger(0)).get();
        }

        public int increaseCounter(ConnectionInfo info) {
            return increaseCounter(info, 1);
        }

        public int increaseCounter(ConnectionInfo info, int amount) {
            return this.connections.computeIfAbsent(info, k -> new AtomicInteger(0)).addAndGet(amount);
        }

        public int decreaseCounter(ConnectionInfo info) {
            return decreaseCounter(info, 1);
        }

        public int decreaseCounter(ConnectionInfo info, int amount) {
            return this.connections.computeIfAbsent(info, k -> new AtomicInteger(0)).addAndGet(-amount);
        }

        public boolean hasNoTrasmitters() {
            for (var e : connections.entrySet()) {
                if (e.getKey().bindType == SmppBindType.RECEIVER) {
                    continue;
                }
                if (e.getValue().get() > 0) {
                    return false;
                }
            }

            return true;
        }

        @Override
        public String toString() {
            return connections.toString();
        }
    }
}

