package gr.cytech.sendium.core.smpp.server;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppServerHandler;
import com.cloudhopper.smpp.SmppServerSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.pdu.BaseBind;
import com.cloudhopper.smpp.pdu.BaseBindResp;
import com.cloudhopper.smpp.tlv.Tlv;
import com.cloudhopper.smpp.type.SmppProcessingException;
import com.google.common.base.Strings;
import gr.cytech.sendium.core.message.StandardMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

public class SmppServerBindHandler<M extends StandardMessage> implements SmppServerHandler {
    private static final Logger logger = LoggerFactory.getLogger(SmppServerBindHandler.class);
    public final ServerConnections connections;
    private final SmppServerWorker<M> worker;
    private final ConcurrentHashMap<Long, SmppSessionContext> pendingSessionContexts;
    private final SmppAuthenticationProvider authProvider;
    private final SubmitSmProcessor submitSmProcessor;

    public SmppServerBindHandler(SmppServerWorker<M> worker, SmppAuthenticationProvider smppAuthenticationProvider, SubmitSmProcessor processor) {
        this.worker = worker;
        authProvider = smppAuthenticationProvider;
        connections = new ServerConnections();
        pendingSessionContexts = new ConcurrentHashMap<>();
        submitSmProcessor = processor;
    }

    public void sessionBindRequested(
            Long sessionId,
            SmppSessionConfiguration sessionConfiguration,
            final BaseBind bindRequest
    ) throws SmppProcessingException {
        logger.info("new bind request: {}", bindRequest);
        checkIfConnectionFromIpIsOverLimit(sessionConfiguration.getHost());
        SmppSessionContext context = authProvider.authenticate(
                sessionConfiguration.getSystemId(),
                sessionConfiguration.getPassword(),
                sessionConfiguration.getHost()
        );
        pendingSessionContexts.put(sessionId, context);
        String accountId = context.getAccountId();

        checkIfConnectionsFromAccountIdIsOverLimit(accountId, context.getMaxConnections());

        sessionConfiguration.setName(accountId);
        sessionConfiguration.setWindowSize(context.getWindowSize());
        sessionConfiguration.setWindowMonitorInterval(context.getWindowMonitorInterval());
        sessionConfiguration.setRequestExpiryTimeout(context.getRequestExpiryTimeout());
        sessionConfiguration.setWriteTimeout(context.getWriteTimeout());

        sessionConfiguration.getLoggingOptions().setLogPdu(worker.getLogPdus());
        sessionConfiguration.getLoggingOptions().setExcludeLogPdus(worker.getExcludeLogPdus());
        sessionConfiguration.getLoggingOptions().setLogBytes(worker.getLogBytes());
        String packageName = SmppServerBindHandler.class.getPackageName();
        sessionConfiguration.getLoggingOptions().setLoggerName(packageName);
        sessionConfiguration.getLoggingOptions().setLogParamPrefix("accId:" + accountId);
    }

    public void sessionCreated(
            Long sessionId,
            SmppServerSession session,
            BaseBindResp preparedBindResponse
    ) throws SmppProcessingException {
        String accountId = session.getConfiguration().getName();
        Tlv optional = new Tlv(SmppConstants.TAG_SC_INTERFACE_VERSION, new byte[]{SmppConstants.VERSION_3_4}, "Interface Version");
        preparedBindResponse.setOptionalParameter(optional);
        SmppSessionContext smppSessionContext = pendingSessionContexts.remove(sessionId);
        var handler = new SmppServerSessionHandler<M>(worker, sessionId, session, smppSessionContext, submitSmProcessor);
        if (smppSessionContext != null && !Strings.isNullOrEmpty(smppSessionContext.getProduct())) {
            handler.setApiProduct(smppSessionContext.getProduct());
        }

        String ip = handler.getSession().getConfiguration().getHost();
        synchronized (connections) {
            checkIfConnectionFromIpIsOverLimit(ip);
            connections.addConnection(accountId, handler);
        }

        logger.info("Session created for account ID: {}", accountId);
        session.serverReady(handler);
    }

    public void sessionDestroyed(Long sessionId, SmppServerSession session) {
        logger.info("Session destroyed: id:{} - name:{}", sessionId, session.getConfiguration().getName());
        if (session.hasCounters()) {
            logger.info("final session rx-submitSM: name:{}-{}", session.getConfiguration().getName(), session.getCounters().getRxSubmitSM());
        }
        connections.removeConnection(session);
        session.destroy();
    }

    public boolean isConnectionReachable(String accountId) {
        return connections.isConnectionReachable(accountId);
    }

    public boolean isSystemIdReachable(String accountId, String systemId) {
        return connections.isSystemIdReachable(accountId, systemId);
    }

    public SmppServerSessionHandler getHandlerForSending(String accountId, String systemId) {
        return connections.getHandlerForSending(accountId, systemId);
    }

    public void checkIfConnectionFromIpIsOverLimit(String ip) throws SmppProcessingException {
        int maxConnectionsPerIp = worker.getMaxConnectionsPerIP();
        int currConnections = connections.getConnectionsSizeForIP(ip);
        logger.debug("checking connection from ip limit, ip:{}, current:{} maxLimit:{}", ip, currConnections, maxConnectionsPerIp);
        if (maxConnectionsPerIp > 0 && currConnections >= maxConnectionsPerIp) {
            logger.warn("Rejecting connection due to max connections per ip limit. ip:{} current:{} limit:{}",
                    ip, currConnections, maxConnectionsPerIp);
            throw new SmppProcessingException(VendorSpecificConstants.STATUS_MAXCONSPERIP, null);
        }
    }

    public void checkIfConnectionsFromAccountIdIsOverLimit(String accountId, int maxConnectionsPerAccount) throws SmppProcessingException {
        int cons = connections.getConnectionsSizeFromAccount(accountId);
        logger.debug("checking connection from accountId limit, accountId:{}, current:{} maxLimit:{}", accountId, cons, maxConnectionsPerAccount);
        if (maxConnectionsPerAccount > 0 && cons >= maxConnectionsPerAccount) {
            logger.warn("Rejecting connection due to max connections per accountId limit. Account:{} current:{} limit:{}",
                    accountId, cons, maxConnectionsPerAccount);
            throw new SmppProcessingException(VendorSpecificConstants.STATUS_MAXCONSPERUSER, null);
        }
    }

    public void configMaxRate(String accountId) {
        connections.configMaxRate(accountId);
    }

    public String getStatistics() {
        return connections.getStatistics();
    }

    public ServerConnections getConnections() {
        return connections;
    }

    public void printStatistics(Logger log) {
        connections.printStatistics(log);
    }

    public void checkInactivityTime(int maxInactivityTime) {
        connections.checkInactivityTime(maxInactivityTime);
    }
}
