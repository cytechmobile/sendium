package smsgateway.smpp.server;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppServerSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.pdu.BaseBind;
import com.cloudhopper.smpp.pdu.BaseBindResp;
import com.cloudhopper.smpp.type.SmppProcessingException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import smsgateway.auth.ApiKeyService;
import smsgateway.providers.LogProvider;
import smsgateway.routing.router.SmsRouter;
import smsgateway.services.ActiveSmppServerSessionService;

@ApplicationScoped
public class SmppServerHandler implements com.cloudhopper.smpp.SmppServerHandler {
    private static final Logger logger =
            LogProvider.getSmppServerLogger(SmppServerHandler.class.getName());

    private final SmppServerConfig config;
    private final SmsRouter smsRouter;
    private final ActiveSmppServerSessionService activeSessionService; // Inject new service
    private final ApiKeyService apiKeyService;

    @Inject
    public SmppServerHandler(
            SmppServerConfig config,
            SmsRouter smsRouter,
            ActiveSmppServerSessionService activeSessionService,
            ApiKeyService apiKeyService) {
        this.config = config;
        this.smsRouter = smsRouter;
        this.activeSessionService = activeSessionService;
        this.apiKeyService = apiKeyService;
    }

    @Override
    // Return type changed to void, throw exception on failure.
    public void sessionBindRequested(
            Long sessionId, SmppSessionConfiguration sessionConfiguration, BaseBind bindRequest)
            throws SmppProcessingException {
        logger.info(
                "SMPP client bind requested. Session ID: {}, System ID: {}",
                sessionId,
                bindRequest.getSystemId());
        if (!apiKeyService.isSmppCredentialsValid(
                bindRequest.getSystemId(), bindRequest.getPassword())) {
            logger.warn(
                    "SMPP client authentication failed for System ID: {}. Invalid credentials.",
                    bindRequest.getSystemId());
            // Correct way to signal bind failure is to throw SmppProcessingException or a subtype.
            // The SmppBindException constructor was problematic. Using SmppProcessingException as
            // per demo.
            throw new SmppProcessingException(SmppConstants.STATUS_BINDFAIL, "Invalid credentials");
        }
        logger.info(
                "SMPP client authenticated successfully. System ID: {}", bindRequest.getSystemId());
        // Apply other session configurations from SmppServerConfig if needed
        sessionConfiguration.setName(
                "Application.SMPP." + bindRequest.getSystemId()); // As seen in demo
        sessionConfiguration
                .getLoggingOptions()
                .setLoggerName(
                        LogProvider.getCategorizedLoggerName(
                                LogProvider.CATEGORY_SMPP_SERVER, sessionConfiguration.getName()));
        sessionConfiguration.setSystemId(bindRequest.getSystemId());
        // sessionConfiguration.setTimeout(config.defaultSessionTimeoutMs()); // Removed
        // sessionConfiguration.setWindowSize(config.getWindowSize()); // Example if you add window
        // size to config
        // On success, do nothing; the library will send the preparedBindResponse
    }

    @Override
    public void sessionCreated(
            Long sessionId, SmppServerSession session, BaseBindResp preparedBindResponse)
            throws SmppProcessingException {
        // Use session.getConfiguration().getSystemId()
        logger.info(
                "SMPP session created. Session ID: {}, Client System ID: {}",
                sessionId,
                session.getConfiguration().getSystemId());
        // Changed to use serverReady with the new PDU handler class name
        session.serverReady(new SmppServerPduHandler(smsRouter, session, sessionId));
        activeSessionService.addSession(sessionId, session);
        logger.info("Attached SmppServerPduHandler to session ID: {}", sessionId);
    }

    @Override
    public void sessionDestroyed(Long sessionId, SmppServerSession session) {
        // Use session.getConfiguration().getSystemId()
        logger.info(
                "SMPP session destroyed. Session ID: {}, Client System ID: {}",
                sessionId,
                (session != null ? session.getConfiguration().getSystemId() : "N/A"));
        // Clean up any resources associated with the session if necessary
        if (session != null && session.hasCounters()) {
            logger.info(
                    "Session Counters for {}: {}",
                    session.getConfiguration().getSystemId(),
                    session.getCounters());
        }
        // Ensure session resources are cleaned up.
        if (session != null) {
            session.destroy();
        }
        activeSessionService.removeSession(sessionId);
    }

    public ActiveSmppServerSessionService getActiveSessionService() {
        return activeSessionService;
    }
}
