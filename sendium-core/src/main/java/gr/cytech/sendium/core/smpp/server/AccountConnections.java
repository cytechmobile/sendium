package gr.cytech.sendium.core.smpp.server;

import com.cloudhopper.smpp.SmppSession;
import gr.cytech.sendium.core.smpp.SmppConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AccountConnections extends SmppConnectionManager<SmppServerSessionHandler> {
    protected static final Logger logger = LoggerFactory.getLogger(AccountConnections.class);
    private final ServerConnections serverConnections;

    public AccountConnections() {
        super();
        serverConnections = new ServerConnections();
    }

    public AccountConnections(ServerConnections serverConnections) {
        super();
        this.serverConnections = serverConnections;
    }

    public void configMaxRate(String accountId) {
        try {
            for (SmppServerSessionHandler handler : handlers) {
                handler.configMaxRate(accountId);
            }
        } catch (Exception e) {
            logger.warn("maxRate configuration failed", e);
        }
    }

    // Override the inactivity check to include the server logic
    @Override
    protected void checkHandlerForInactivity(SmppServerSessionHandler handler, long now, int maxInactivityTime) {
        int currentInactiveTime = (int) (now - handler.getLastPduTimestamp()) / 60000;
        if (currentInactiveTime >= maxInactivityTime) {
            logger.warn("Destroying SERVER session {} because of inactivity time {}",
                    "[SystemID:" + handler.getSession().getConfiguration().getSystemId() + "]",
                    currentInactiveTime);
            if (handler.getSession() != null && SmppSession.Type.SERVER.equals(handler.getSession().getLocalType())) {
                handler.getSession().destroy();
                serverConnections.removeConnection(handler);
            }
        }
    }
}
