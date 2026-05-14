package gr.cytech.sendium.core.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.h2.mvstore.MVStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.Serializable;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class InMemoryDlrService implements Serializable {
    private static final Logger logger = LoggerFactory.getLogger(InMemoryDlrService.class);
    private static final long SEVEN_DAYS_MILLIS = TimeUnit.DAYS.toMillis(7);
    private static final long THREE_DAYS_MILLIS = TimeUnit.DAYS.toMillis(3);
    private static final long serialVersionUID = 1L;
    private static final long EXPIRY_CHECK_INTERVAL = TimeUnit.HOURS.toMillis(1);

    private static final String DB_PATH_PROPERTY = "sendium.dlr.db.path";
    private static final String DEFAULT_DB_PATH = "data/dlr-mvstore.db";
    private static final ObjectMapper mapper = new ObjectMapper();

    @Inject
    ForwardDlrService forwardDlrService;

    private transient MVStore store;

    private transient Map<String, String> primaryStore;
    private transient Map<String, String> correlationIndex;
    private transient Map<String, Long> primaryTimestamps;
    private transient Map<String, Long> correlationTimestamps;
    private transient volatile long lastExpiryCheck = 0;
    @SuppressWarnings("unused")
    private transient volatile boolean initialized = false;

    @PostConstruct
    void init() {
        String dbPath = System.getProperty(DB_PATH_PROPERTY, DEFAULT_DB_PATH);
        File dbFile = new File(dbPath);
        File dbDir = dbFile.getParentFile();

        if (dbDir != null && !dbDir.exists()) {
            boolean created = dbDir.mkdirs();
            if (created) {
                logger.info("Created DLR database directory: {}", dbDir.getAbsolutePath());
            }
        }

        try {
            if (dbFile.exists() && dbFile.length() > 0) {
                store = new MVStore.Builder()
                        .fileName(dbFile.getAbsolutePath())
                        .autoCommitBufferSize(1024)
                        .open();
                logger.info("Opened existing DLR database: " + dbPath);
            } else {
                store = new MVStore.Builder()
                        .fileName(dbFile.getAbsolutePath())
                        .open();
                logger.info("Created new DLR database: " + dbPath);
            }

            primaryStore = store.openMap("primaryStore");
            correlationIndex = store.openMap("correlationIndex");
            primaryTimestamps = store.openMap("primaryTimestamps");
            correlationTimestamps = store.openMap("correlationTimestamps");

            if (primaryStore == null || correlationIndex == null) {
                logger.error("Failed to load maps from DB, falling back to in-memory");
                fallbackToInMemory();
            } else {
                logger.info("Loaded from DB - primaryStore: {}, correlationIndex: {}",
                        primaryStore.size(), correlationIndex.size());
                initialized = true;
            }
        } catch (Exception e) {
            logger.warn("Failed to initialize MVStore, falling back to in-memory: ", e);
            fallbackToInMemory();
        }

        if (!initialized) {
            fallbackToInMemory();
        }
    }

    private void fallbackToInMemory() {
        store = null;
        primaryStore = new ConcurrentHashMap<>();
        correlationIndex = new ConcurrentHashMap<>();
        primaryTimestamps = new ConcurrentHashMap<>();
        correlationTimestamps = new ConcurrentHashMap<>();
        initialized = true;
        logger.info("Using in-memory mode (no persistence)");
    }

    void onStop(@Observes ShutdownEvent ev) {
        logger.info("InMemoryDlrService shutting down");
        saveAndClose();
    }

    private synchronized void saveAndClose() {
        if (store != null && !store.isClosed()) {
            try {
                store.commit();
                logger.info("Saved DLR database");
            } catch (Exception e) {
                logger.warn("Failed to commit DB: {}", e.getMessage());
            }
            try {
                store.close();
                logger.info("Closed DLR database");
            } catch (Exception e) {
                logger.warn("Failed to close DB: {}", e.getMessage());
            }
        }
    }

    public void saveInitialState(MessageState context) {
        if (primaryStore != null) {
            checkExpiry();
            try {
                String json = mapper.writeValueAsString(context);
                primaryStore.put(context.getGatewayMsgId(), json);
                primaryTimestamps.put(context.getGatewayMsgId(), System.currentTimeMillis());
                logger.info("Saved initial state on primaryStore for gatewayMsgId: {}", context.getGatewayMsgId());
            } catch (JsonProcessingException e) {
                logger.error("Failed to serialize MessageState for gatewayMsgId: {}", context.getGatewayMsgId(), e);
            }
        }
    }

    public void linkOperatorId(String gatewayMsgId, String operatorMsgId) {
        checkExpiry();
        if (primaryStore == null || correlationIndex == null) {
            return;
        }

        int maxRetries = 20;
        long retryIntervalMs = 200;
        MessageState state = null;

        for (int i = 0; i < maxRetries; i++) {
            String stateJson = primaryStore.get(gatewayMsgId);
            if (stateJson != null) {
                try {
                    // Deserialize back to object
                    state = mapper.readValue(stateJson, MessageState.class);
                    break; // State found and parsed, exit the retry loop
                } catch (JsonProcessingException e) {
                    logger.error("Failed to deserialize MessageState for gatewayMsgId: {}", gatewayMsgId, e);
                    break;
                }
            }
            try {
                Thread.sleep(retryIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Thread interrupted while retrying for gatewayMsgId: {}", gatewayMsgId);
                break;
            }
        }

        // Check if we successfully found the state after the retries
        if (state != null) {
            state.setOperatorMsgId(operatorMsgId);
            state.setStatus(MessageState.MessageStatus.SENT);
            state.setTimestamp(System.currentTimeMillis());
            try {
                primaryStore.put(gatewayMsgId, mapper.writeValueAsString(state));
                correlationIndex.put(operatorMsgId, gatewayMsgId);
                correlationTimestamps.put(operatorMsgId, System.currentTimeMillis());
                logger.warn("Linked operatorMsgId: {} to gatewayMsgId: {}", operatorMsgId, gatewayMsgId);
            } catch (JsonProcessingException e) {
                logger.error("Failed to serialize updated MessageState", e);
            }
        } else {
            logger.warn("GatewayMsgId not found for linking after {} retries: {}", maxRetries, gatewayMsgId);
        }
    }

    public Optional<MessageState> resolveAndRemoveDlr(String operatorMsgId, int dlrState) {
        checkExpiry();
        if (correlationIndex == null || primaryStore == null) {
            return Optional.empty();
        }
        String gatewayMsgId = correlationIndex.get(operatorMsgId);
        if (gatewayMsgId == null) {
            logger.warn("No gatewayMsgId found for operatorMsgId: {} (expired or unknown)", operatorMsgId);
            return Optional.empty();
        }

        String stateJson = primaryStore.get(gatewayMsgId);
        if (stateJson != null) {
            try {
                MessageState state = mapper.readValue(stateJson, MessageState.class);
                state.setTimestamp(System.currentTimeMillis());
                state.setStatus(mapDlrStateToMessageStatus(dlrState));

                primaryStore.remove(gatewayMsgId);
                primaryTimestamps.remove(gatewayMsgId);
                correlationIndex.remove(operatorMsgId);
                correlationTimestamps.remove(operatorMsgId);
                logger.debug("Resolved and removed DLR for gatewayMsgId: {}", gatewayMsgId);

                String forwardUrl = state.getForwardDlrUrl();
                if (forwardUrl != null && !forwardUrl.isEmpty()) {
                    forwardDlrService.forwardDlr(state);
                }

                return Optional.of(state);
            } catch (JsonProcessingException e) {
                logger.error("Failed to deserialize MessageState during resolve", e);
            }
        }

        logger.warn("MessageState not found for gatewayMsgId: {}", gatewayMsgId);
        return Optional.empty();
    }

    private MessageState.MessageStatus mapDlrStateToMessageStatus(int dlrState) {
        return switch (dlrState) {
            case 1 -> MessageState.MessageStatus.DELIVERED;
            case 2, 3, 4, 6, 7, 8 -> MessageState.MessageStatus.FAILED;
            case 5, 9 -> MessageState.MessageStatus.ACCEPTED;
            case 15 -> MessageState.MessageStatus.DELIVERED;
            default -> MessageState.MessageStatus.FAILED;
        };
    }

    public Optional<MessageState> getState(String gatewayMsgId) {
        checkExpiry();
        if (primaryStore == null) {
            return Optional.empty();
        }

        String stateJson = primaryStore.get(gatewayMsgId);
        if (stateJson != null) {
            try {
                return Optional.of(mapper.readValue(stateJson, MessageState.class));
            } catch (JsonProcessingException e) {
                logger.error("Failed to deserialize MessageState in getState", e);
            }
        }
        return Optional.empty();
    }

    public boolean markAsFailed(String gatewayMsgId) {
        checkExpiry();
        if (primaryStore == null) {
            return false;
        }

        String stateJson = primaryStore.get(gatewayMsgId);
        if (stateJson != null) {
            try {
                MessageState state = mapper.readValue(stateJson, MessageState.class);
                state.setStatus(MessageState.MessageStatus.FAILED);
                state.setTimestamp(System.currentTimeMillis());
                primaryStore.put(gatewayMsgId, mapper.writeValueAsString(state));
                return true;
            } catch (JsonProcessingException e) {
                logger.error("Failed to process MessageState in markAsFailed", e);
            }
        }
        return false;
    }

    private synchronized void checkExpiry() {
        long now = System.currentTimeMillis();
        if (now - lastExpiryCheck < EXPIRY_CHECK_INTERVAL) {
            return;
        }
        lastExpiryCheck = now;

        if (primaryStore == null || primaryTimestamps == null) {
            return;
        }

        for (String key : primaryTimestamps.keySet()) {
            Long ts = primaryTimestamps.get(key);
            if (ts != null && (now - ts) > SEVEN_DAYS_MILLIS) {
                primaryStore.remove(key);
                primaryTimestamps.remove(key);
                logger.debug("Expired primary entry: {}", key);
            }
        }

        if (correlationIndex != null && correlationTimestamps != null) {
            for (String key : correlationTimestamps.keySet()) {
                Long ts = correlationTimestamps.get(key);
                if (ts != null && (now - ts) > THREE_DAYS_MILLIS) {
                    correlationIndex.remove(key);
                    correlationTimestamps.remove(key);
                    logger.debug("Expired correlation entry: {}", key);
                }
            }
        }
    }

    public int getPrimaryStoreSize() {
        return primaryStore != null ? primaryStore.size() : 0;
    }

    public int getCorrelationIndexSize() {
        return correlationIndex != null ? correlationIndex.size() : 0;
    }

    public boolean isPersistent() {
        return store != null && !store.isClosed();
    }
}