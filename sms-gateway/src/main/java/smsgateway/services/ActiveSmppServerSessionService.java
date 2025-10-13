package smsgateway.services;

import com.cloudhopper.smpp.SmppServerSession;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import smsgateway.providers.LogProvider;

@ApplicationScoped
public class ActiveSmppServerSessionService {
    private static final Logger LOGGER =
            LogProvider.getSmppServerLogger(ActiveSmppServerSessionService.class.getName());
    private final Map<Long, SmppServerSession> activeSessions = new ConcurrentHashMap<>();

    public void addSession(Long sessionId, SmppServerSession session) {
        LOGGER.info("Tracking new SMPP server session: {}", sessionId);
        activeSessions.put(sessionId, session);
    }

    public void removeSession(Long sessionId) {
        LOGGER.info("Stopped tracking SMPP server session: {}", sessionId);
        activeSessions.remove(sessionId);
    }

    public Optional<SmppServerSession> getSession(Long sessionId) {
        return Optional.ofNullable(activeSessions.get(sessionId));
    }
}
