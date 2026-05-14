package gr.cytech.sendium.core.smpp.server;

import com.cloudhopper.smpp.SmppServerSession;
import com.cloudhopper.smpp.SmppSession;
import com.google.common.base.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ServerConnections {
    public final ConcurrentMap<String, AccountConnections> connections;
    public final ConcurrentMap<String, AtomicInteger> conPerIPCounters;

    protected Logger logger = LoggerFactory.getLogger(this.getClass());

    public ServerConnections() {
        connections = new ConcurrentHashMap<>(8);
        conPerIPCounters = new ConcurrentHashMap<>(8);
    }

    public synchronized void addConnection(String accountId, SmppServerSessionHandler handler) {
        if (connections.get(accountId) == null) {
            connections.putIfAbsent(accountId, new AccountConnections(this));
        }
        boolean added = connections.get(accountId).add(handler);
        logger.info("new session handler for account:{} was added:{}", accountId, added);

        //increase the connections counter for this specific IP
        String ip = handler.getSession().getConfiguration().getHost();
        conPerIPCounters.putIfAbsent(ip, new AtomicInteger());
        conPerIPCounters.get(ip).incrementAndGet();

        logger.debug("Count of connections per ip for ip:{} {}", ip, conPerIPCounters.get(ip).get());
    }

    public synchronized boolean removeConnection(SmppServerSessionHandler handler, SmppSession session) {
        if (session == null) {
            logger.warn("trying to remove a null session from account connections, rejecting");
            return false;
        }

        boolean removed = false;
        String accountId = session.getConfiguration().getName();
        AccountConnections accountConnections = connections.get(accountId);
        if (accountConnections != null) {
            removed = accountConnections.remove(handler);
            if (accountConnections.isEmpty()) {
                connections.remove(accountId);
            }
        }
        String ip = session.getConfiguration().getHost();
        if (conPerIPCounters.containsKey(ip)) {
            int cons = conPerIPCounters.get(ip).decrementAndGet();
            if (cons <= 0) {
                conPerIPCounters.remove(ip);
            }
        }

        return removed;
    }

    public boolean removeConnection(SmppServerSessionHandler handler) {
        return handler != null && handler.getSession() != null && removeConnection(handler, handler.getSession());
    }

    public boolean removeConnection(SmppServerSession session) {
        String accountId = session.getConfiguration().getName();
        AccountConnections accountConnections = connections.get(accountId);
        if (accountConnections != null) {
            return removeConnection(accountConnections.getHandlerOfSession(session));
        } else {
            return false;
        }
    }

    public boolean isConnectionReachable(String accountId) {
        return connections.containsKey(accountId) && connections.get(accountId).hasTransmittableConnections();
    }

    public boolean isSystemIdReachable(String accountId, String systemId) {
        return Optional.ofNullable(connections.get(accountId))
            .map(accountConnections -> accountConnections.hasTransmittableConnections(systemId))
            .orElse(false);
    }

    public AccountConnections getAccountConnections(String accountId) {
        return connections.get(accountId);
    }

    public SmppServerSessionHandler getHandlerForSending(String accountId, String systemId) {
        var accountConnections = connections.get(accountId);
        if (accountConnections == null) {
            return null;
        }
        if (Strings.isNullOrEmpty(systemId)) {
            return accountConnections.getAvailableHandlerForSending();
        }
        return accountConnections.getAvailableHandlerSystemIdForSending(systemId);
    }

    public int getConnectionsSizeForIP(String ip) {
        return conPerIPCounters.containsKey(ip) ?
                conPerIPCounters.get(ip).get() : 0;
    }

    public int getConnectionsSizeFromAccount(String accountId) {
        return connections.containsKey(accountId) ?
                connections.get(accountId).size() : 0;
    }

    public int getTransmittableConnectionsSizeFromAccount(String accountId) {
        return connections.containsKey(accountId) ?
                connections.get(accountId).getTransmittableConnectionsSize() : 0;
    }

    public boolean hasConnections(String accountId) {
        return connections.containsKey(accountId) &&
                !connections.get(accountId).isEmpty();
    }

    public boolean hasTransmittableConnections(String accountId) {
        return connections.containsKey(accountId) &&
                connections.get(accountId).hasTransmittableConnections();
    }

    public String getStatistics() {
        StringBuilder statistics = new StringBuilder();

        for (var accountConnections : connections.values()) {
            statistics.append("\n");
            statistics.append(accountConnections.getStatistics());
        }

        return statistics.toString();
    }

    public void printStatistics(Logger log) {
        String statistics = getStatistics();
        String[] lines = statistics.split("\\n");

        for (String line : lines) {
            log.info(line);
        }

    }

    public void configMaxRate(String accountId) {
        if (accountId == null) {
            for (String account : connections.keySet()) {
                connections.get(account).configMaxRate(account);
            }
        } else {
            var uc = connections.get(accountId);
            if (uc != null) {
                uc.configMaxRate(accountId);
            }
        }
    }

    public void checkInactivityTime(int maxInactivityTime) {
        for (var connection : connections.values()) {
            connection.checkInactivityTime(maxInactivityTime);
        }
    }
}
