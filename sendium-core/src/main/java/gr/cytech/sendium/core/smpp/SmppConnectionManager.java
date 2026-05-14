package gr.cytech.sendium.core.smpp;

import com.cloudhopper.commons.util.LoadBalancedList;
import com.cloudhopper.commons.util.LoadBalancedLists;
import com.cloudhopper.commons.util.RoundRobinLoadBalancedList;
import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionCounters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SmppConnectionManager<T extends SmsgSmppSessionHandler> {
    protected static final Logger logger = LoggerFactory.getLogger(SmppConnectionManager.class);

    public final Set<T> handlers;
    /**
     * for the server: transmittables contains all connections that the server can use to send requests (deliver_sm)
     * which means they have to be either transceivers or receivers
     * <br />
     * for the client: transmittables contains all connections that the client can use to send requests (submit_sm)
     * which means they have to be either transceivers or transmitters
     */
    public final LoadBalancedList<T> transmittables;
    public final Map<String, LoadBalancedList<T>> systemIdTransmittables;
    protected final LoadBalancedList<T> backupTransmittables;

    public SmppConnectionManager() {
        handlers = Collections.newSetFromMap(new ConcurrentHashMap<>());
        transmittables = LoadBalancedLists.synchronizedList(new RoundRobinLoadBalancedList<>());
        systemIdTransmittables = new ConcurrentHashMap<>();
        backupTransmittables = LoadBalancedLists.synchronizedList(new RoundRobinLoadBalancedList<>());
    }

    public static boolean isTransmittable(SmppBindType bindType, SmppSession.Type localType) {
        return bindType == SmppBindType.TRANSCEIVER ||
                bindType == SmppBindType.TRANSMITTER && localType == SmppSession.Type.CLIENT ||
                bindType == SmppBindType.RECEIVER && localType == SmppSession.Type.SERVER;
    }

    public boolean add(T handler) {
        if (handler == null || handler.getSession() == null || handler.getSession().getBindType() == null) {
            logger.debug("Trying to add empty handler/session, rejected {}", handler);
            return false;
        }

        if (isTransmittable(handler.getSession().getBindType(), handler.getSession().getLocalType())) {
            if (handler.isBackupConnection()) {
                backupTransmittables.set(handler, Integer.MAX_VALUE);
            } else {
                transmittables.set(handler, Integer.MAX_VALUE);
                systemIdTransmittables.compute(handler.getSession().getConfiguration().getSystemId(),
                        (key, existingList) -> {
                            if (existingList == null) {
                                //first key
                                existingList = LoadBalancedLists.synchronizedList(new RoundRobinLoadBalancedList<>());
                            }
                            existingList.set(handler, Integer.MAX_VALUE);
                            return existingList;
                        }
                );
            }
        }

        return handlers.add(handler);
    }

    public boolean remove(T handler) {
        if (handler == null) {
            logger.debug("trying to remove empty handler, rejecting...");
            return false;
        }

        if (handler.getSession() == null || handler.getSession().getBindType() == null ||
                isTransmittable(handler.getSession().getBindType(), handler.getSession().getLocalType())) {
            logger.debug("removing trasmittable (or unidentified with empty session) handler");
            //try to remove handler from all sets
            if (handler.isBackupConnection()) {
                backupTransmittables.remove(handler);
            } else {
                transmittables.remove(handler);
                for (var entry : systemIdTransmittables.entrySet()) {
                    systemIdTransmittables.compute(entry.getKey(), (k, v) -> {
                        if (v == null) {
                            return null;
                        }
                        v.remove(handler);
                        if (v.getSize() == 0) {
                            return null;
                        }
                        return v;
                    });
                }
            }
        }
        return handlers.remove(handler);
    }

    public void clear() {
        handlers.clear();
        transmittables.clear();
        if (systemIdTransmittables != null) {
            systemIdTransmittables.clear();
        }
        backupTransmittables.clear();
    }

    public boolean hasConnections() {
        return !isEmpty();
    }

    public boolean isEmpty() {
        return handlers.isEmpty();
    }

    public int size() {
        return handlers.size();
    }

    public boolean hasTransmittableConnections() {
        return transmittables.getSize() > 0;
    }

    public boolean hasTransmittableConnections(String systemId) {
        var list = systemIdTransmittables.get(systemId);
        return list != null && list.getSize() > 0;
    }

    public int getTransmittableConnectionsSize() {
        return transmittables.getSize();
    }

    public T getAvailableHandlerSystemIdForSending(String systemId) {
        var list = systemIdTransmittables.get(systemId);
        if (list == null) {
            return null;
        }
        return getAvailableHandlerForSending(list);
    }

    public T getAvailableHandlerForSending() {
        T handler = getAvailableHandlerForSending(transmittables);
        if (handler != null) {
            return handler;
        }

        return getAvailableHandlerForSending(backupTransmittables);
    }

    public static <T extends SmsgSmppSessionHandler> T getAvailableHandlerForSending(LoadBalancedList<T> list) {
        int transmittablesSize = list.getSize();
        for (int i = 0; i < transmittablesSize; i++) {
            T handler = list.getNext();
            if (handler != null && handler.getSession() != null && handler.getSession().isBound() &&
                    handler.getSession().getSendWindow().getFreeSize() > 0) {
                return handler;
            }
        }

        //no transmitter with straight free window slot, so just return the next and hope that one slot
        //will become free in the immediate future
        return list.getNext();
    }

    public LoadBalancedList<T> getTransmittables() {
        return transmittables;
    }

    public Set<T> getHandlers() {
        return handlers;
    }

    public Set<T> getAllHandlers() {
        return new HashSet<>(handlers);
    }

    public T getHandlerOfSession(SmppSession session) {
        if (session == null) {
            return null;
        }

        for (T handler : handlers) {
            if (session.equals(handler.getSession())) {
                return handler;
            }
        }

        return null;
    }

    public String getStatistics() {
        StringBuilder statistics = new StringBuilder();

        for (T handler : handlers) {
            statistics.append(getStatistics(handler));
            statistics.append("\n");
        }
        return statistics.toString();
    }

    public String getStatistics(T handler) {
        StringBuilder statistics = new StringBuilder();
        SmppSession session = handler.getSession();

        statistics.append("  ");
        statistics.append(session.getConfiguration().getType());
        statistics.append("[");
        statistics.append("UserId=");
        statistics.append(session.getConfiguration().getName());
        statistics.append(" SystemId=");
        statistics.append(session.getConfiguration().getSystemId());
        statistics.append(" Host=");
        statistics.append(session.getConfiguration().getHost());
        statistics.append(" Port=");
        statistics.append(session.getConfiguration().getPort());
        statistics.append(" SystemType=");
        statistics.append(session.getConfiguration().getSystemType());
        statistics.append(" MaxPending=");
        statistics.append(session.getConfiguration().getWindowSize());
        statistics.append(" ResponseTimeout=");
        statistics.append(session.getConfiguration().getRequestExpiryTimeout());
        statistics.append(" WindowMonitorInterval=");
        statistics.append(session.getConfiguration().getWindowMonitorInterval());
        statistics.append(" TransactionsPerSec=");
        statistics.append(handler.getRate());
        statistics.append("]");

        SmppSessionCounters counters = session.getCounters();

        statistics.append("\n");
        statistics.append("    submit_sm    =");
        statistics.append(counters.getRxSubmitSM());
        statistics.append("\n");
        statistics.append("    submit_sm_rsp=");
        statistics.append(counters.getTxSubmitSM());
        statistics.append("\n");
        statistics.append("    deliver_sm   =");
        statistics.append(counters.getTxDeliverSM());
        statistics.append("\n");
        statistics.append("    enquire_link =");
        statistics.append(counters.getRxEnquireLink());

        return statistics.toString();
    }

    public void checkInactivityTime(int maxInactivityTime) {
        long now = System.currentTimeMillis();

        for (T handler : handlers) {
            checkHandlerForInactivity(handler, now, maxInactivityTime);
        }
    }

    protected void checkHandlerForInactivity(T handler, long now, int maxInactivityTime) {
        int currentInactiveTime = (int) (now - handler.getLastPduTimestamp()) / 60000;
        if (currentInactiveTime >= maxInactivityTime) {
            logger.warn("Destroying session {} because of inactivity time {}",
                    "[SystemID:" + handler.getSession().getConfiguration().getSystemId() + "]",
                    currentInactiveTime);
            if (handler.getSession() != null) {
                handler.getSession().destroy();
            }
        }
    }
}
