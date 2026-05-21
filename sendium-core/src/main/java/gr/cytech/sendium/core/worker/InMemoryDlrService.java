package gr.cytech.sendium.core.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.cytech.sendium.core.message.StandardMessage;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.h2.mvstore.MVStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Stores DLR correlation state and unpushed SMPP DLRs.
 *
 * <p>
 * The service uses H2 MVStore when available and falls back to in-memory maps if the store cannot be opened.
 * The primary/correlation maps track submitted messages until operator DLRs arrive. The unpushed-DLR maps
 * persist DLRs that could not be delivered to a disconnected SMPP client, then replay them when the matching
 * systemId reconnects.
 *
 * <p>
 * This is an application-scoped singleton. The primary/correlation state follows the existing model of map-level
 * concurrency: each operation is safe to call from worker threads, but multi-step updates are not globally serialized.
 * Unpushed DLRs have stronger consistency requirements because each entry is split across payload, timestamp, and
 * systemId index maps. Those compound operations are guarded by {@code unpushedDlrLock}. Replay also claims keys
 * before returning them so concurrent reconnect callbacks for the same systemId cannot enqueue the same DLR twice.
 */
@ApplicationScoped
public class InMemoryDlrService {
    private static final Logger logger = LoggerFactory.getLogger(InMemoryDlrService.class);
    private static final long SEVEN_DAYS_MILLIS = TimeUnit.DAYS.toMillis(7);
    private static final long THREE_DAYS_MILLIS = TimeUnit.DAYS.toMillis(3);
    private static final long EXPIRY_CHECK_INTERVAL = TimeUnit.HOURS.toMillis(1);

    private static final String DB_PATH_PROPERTY = "sendium.dlr.db.path";
    private static final String DEFAULT_DB_PATH = "data/dlr-mvstore.db";
    private static final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    @Inject
    ForwardDlrService forwardDlrService;

    private final Object unpushedDlrStateLock = new Object();
    private final Set<String> claimedUnpushedDlrKeys = ConcurrentHashMap.newKeySet();

    private MVStore store;

    private Map<String, String> primaryStore;
    private Map<String, String> correlationIndex;
    private Map<String, Long> primaryTimestamps;
    private Map<String, Long> correlationTimestamps;
    private Map<String, String> unpushedDlrStore;
    private Map<String, Long> unpushedDlrTimestamps;
    private Map<String, String> unpushedDlrIndex;

    private volatile long lastExpiryCheck = 0;
    @SuppressWarnings("unused")
    private volatile boolean initialized = false;

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
            unpushedDlrStore = store.openMap("unpushedDlrStore");
            unpushedDlrTimestamps = store.openMap("unpushedDlrTimestamps");
            unpushedDlrIndex = store.openMap("unpushedDlrIndex");

            if (primaryStore == null || correlationIndex == null || unpushedDlrStore == null || unpushedDlrIndex == null) {
                logger.error("Failed to load maps from DB, falling back to in-memory");
                fallbackToInMemory();
            } else {
                logger.info("Loaded from DB - primaryStore: {}, correlationIndex: {}, unpushedDlrStore: {}, unpushedDlrIndex: {}",
                        primaryStore.size(), correlationIndex.size(), unpushedDlrStore.size(), unpushedDlrIndex.size());
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
        unpushedDlrStore = new ConcurrentHashMap<>();
        unpushedDlrTimestamps = new ConcurrentHashMap<>();
        unpushedDlrIndex = new ConcurrentHashMap<>();
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

    /**
     * Persist a DLR that could not be pushed to the SMPP client.
     */
    public boolean saveUnpushedDlr(StandardMessage msg) {
        checkExpiry();
        if (unpushedDlrStore == null || unpushedDlrIndex == null || msg == null || msg.type != StandardMessage.MSG_DLR ||
                msg.systemId == null || msg.systemId.isBlank()) {
            return false;
        }

        String key = getUnpushedDlrKey(msg);
        synchronized (unpushedDlrStateLock) {
            try {
                unpushedDlrStore.put(key, mapper.writeValueAsString(UnpushedDlr.fromMessage(msg)));
                unpushedDlrTimestamps.put(key, System.currentTimeMillis());
                addKeyToUnpushedDlrIndex(msg.systemId, key);
                commitStore();
                logger.info("Saved unpushed DLR key: {}", key);
                return true;
            } catch (JsonProcessingException e) {
                logger.error("Failed to serialize unpushed DLR key: {}", key, e);
                return false;
            }
        }
    }

    /**
     * Load unpushed DLRs for one SMPP systemId without marking them for replay.
     */
    public List<StandardMessage> getUnpushedDlrs(String systemId) {
        return loadUnpushedDlrs(systemId, false);
    }

    /**
     * Load and claim unpushed DLRs for replay. Claimed entries are hidden from later claims until removed or released.
     */
    public List<StandardMessage> claimUnpushedDlrs(String systemId) {
        return loadUnpushedDlrs(systemId, true);
    }

    private List<StandardMessage> loadUnpushedDlrs(String systemId, boolean claimForReplay) {
        checkExpiry();
        List<StandardMessage> messages = new ArrayList<>();
        if (unpushedDlrStore == null || unpushedDlrIndex == null || systemId == null || systemId.isBlank()) {
            return messages;
        }

        boolean changed = false;
        synchronized (unpushedDlrStateLock) {
            for (String key : getUnpushedDlrKeys(systemId)) {
                if (claimForReplay && claimedUnpushedDlrKeys.contains(key)) {
                    continue;
                }

                String msgJson = unpushedDlrStore.get(key);
                if (msgJson == null) {
                    removeKeyFromUnpushedDlrIndex(systemId, key);
                    changed = true;
                    continue;
                }
                try {
                    UnpushedDlr dlr = mapper.readValue(msgJson, UnpushedDlr.class);
                    if (isUnpushedDlrForConnection(dlr, systemId)) {
                        if (!claimForReplay || claimedUnpushedDlrKeys.add(key)) {
                            messages.add(dlr.toMessage());
                        }
                    } else {
                        removeKeyFromUnpushedDlrIndex(systemId, key);
                        changed = true;
                    }
                } catch (JsonProcessingException e) {
                    logger.error("Failed to deserialize unpushed DLR key: {}. Removing corrupt entry", key, e);
                    unpushedDlrStore.remove(key);
                    unpushedDlrTimestamps.remove(key);
                    claimedUnpushedDlrKeys.remove(key);
                    removeKeyFromUnpushedDlrIndex(systemId, key);
                    changed = true;
                }
            }
            if (changed) {
                commitStore();
            }
        }

        return messages;
    }

    /**
     * Remove a replayed DLR from all unpushed-DLR maps.
     */
    public boolean removeUnpushedDlr(StandardMessage msg) {
        if (unpushedDlrStore == null || unpushedDlrIndex == null || msg == null || msg.systemId == null || msg.systemId.isBlank()) {
            return false;
        }

        String key = getUnpushedDlrKey(msg);
        synchronized (unpushedDlrStateLock) {
            final boolean removed = unpushedDlrStore.remove(key) != null;
            unpushedDlrTimestamps.remove(key);
            claimedUnpushedDlrKeys.remove(key);
            removeKeyFromUnpushedDlrIndex(msg.systemId, key);
            if (removed) {
                commitStore();
            }
            return removed;
        }
    }

    /**
     * Make a claimed but not yet removed DLR eligible for a later replay attempt.
     */
    public void releaseUnpushedDlrClaim(StandardMessage msg) {
        if (msg == null || msg.systemId == null || msg.systemId.isBlank()) {
            return;
        }

        synchronized (unpushedDlrStateLock) {
            claimedUnpushedDlrKeys.remove(getUnpushedDlrKey(msg));
        }
    }

    private boolean isUnpushedDlrForConnection(UnpushedDlr dlr, String systemId) {
        return dlr != null && dlr.systemId != null && dlr.systemId.equals(systemId);
    }

    private String getUnpushedDlrKey(StandardMessage msg) {
        return String.join("|",
                nullToEmpty(msg.systemId),
                nullToEmpty(msg.serial),
                String.valueOf(msg.state),
                nullToEmpty(msg.errcode),
                String.valueOf(msg.msgId));
    }

    private void addKeyToUnpushedDlrIndex(String systemId, String key) throws JsonProcessingException {
        List<String> keys = getUnpushedDlrKeys(systemId);
        if (!keys.contains(key)) {
            keys.add(key);
            unpushedDlrIndex.put(systemId, mapper.writeValueAsString(keys));
        }
    }

    private List<String> getUnpushedDlrKeys(String systemId) {
        String keysJson = unpushedDlrIndex.get(systemId);
        if (keysJson == null || keysJson.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(mapper.readValue(keysJson, STRING_LIST_TYPE));
        } catch (JsonProcessingException e) {
            logger.error("Failed to deserialize unpushed DLR index for systemId: {}. Clearing corrupt index", systemId, e);
            unpushedDlrIndex.remove(systemId);
            return new ArrayList<>();
        }
    }

    private void removeKeyFromUnpushedDlrIndex(String systemId, String key) {
        if (systemId == null || unpushedDlrIndex == null) {
            return;
        }
        List<String> keys = getUnpushedDlrKeys(systemId);
        if (!keys.remove(key)) {
            return;
        }
        if (keys.isEmpty()) {
            unpushedDlrIndex.remove(systemId);
            return;
        }
        try {
            unpushedDlrIndex.put(systemId, mapper.writeValueAsString(keys));
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize unpushed DLR index for systemId: {}. Clearing index", systemId, e);
            unpushedDlrIndex.remove(systemId);
        }
    }

    private String getSystemIdFromUnpushedDlrKey(String key) {
        int separator = key.indexOf('|');
        return separator >= 0 ? key.substring(0, separator) : key;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void commitStore() {
        if (store != null && !store.isClosed()) {
            store.commit();
        }
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

        boolean removedExpired = false;
        if (unpushedDlrStore != null && unpushedDlrTimestamps != null) {
            synchronized (unpushedDlrStateLock) {
                for (String key : unpushedDlrTimestamps.keySet()) {
                    Long ts = unpushedDlrTimestamps.get(key);
                    if (ts != null && (now - ts) > SEVEN_DAYS_MILLIS) {
                        unpushedDlrStore.remove(key);
                        unpushedDlrTimestamps.remove(key);
                        claimedUnpushedDlrKeys.remove(key);
                        removeKeyFromUnpushedDlrIndex(getSystemIdFromUnpushedDlrKey(key), key);
                        removedExpired = true;
                        logger.debug("Expired unpushed DLR entry: {}", key);
                    }
                }
            }
        }
        if (removedExpired) {
            commitStore();
        }
    }

    public int getPrimaryStoreSize() {
        return primaryStore != null ? primaryStore.size() : 0;
    }

    public int getCorrelationIndexSize() {
        return correlationIndex != null ? correlationIndex.size() : 0;
    }

    public int getUnpushedDlrStoreSize() {
        return unpushedDlrStore != null ? unpushedDlrStore.size() : 0;
    }

    public int getUnpushedDlrIndexSize() {
        return unpushedDlrIndex != null ? unpushedDlrIndex.size() : 0;
    }

    public boolean isPersistent() {
        return store != null && !store.isClosed();
    }
}
