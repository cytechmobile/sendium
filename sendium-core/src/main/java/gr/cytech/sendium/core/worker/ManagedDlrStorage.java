package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundDlrPolicy;
import gr.cytech.sendium.util.MessageTrace;
import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Startup
@ApplicationScoped
@IfBuildProperty(name = "sendium.dlr.persistence.enabled", stringValue = "true", enableIfMissing = false)
public class ManagedDlrStorage implements DlrStorage {
    private static final Logger logger = LoggerFactory.getLogger(ManagedDlrStorage.class);
    private static final String METRIC_NAME = "sendium.dlr.storage.operation";

    @Inject
    @ConfigProperty(name = "quarkus.flyway.dlr.active", defaultValue = "false")
    boolean flywayActive;

    @Inject
    @ConfigProperty(name = "quarkus.flyway.dlr.migrate-at-start", defaultValue = "false")
    boolean flywayMigrateAtStart;

    @Inject
    @DataSource("dlr")
    InjectableInstance<AgroalDataSource> postgresqlDataSource;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    @ConfigProperty(name = "sendium.dlr.delivery-claim-duration", defaultValue = "5M")
    Duration deliveryClaimDuration;

    // Read once at startup; there is no runtime backend switching or fallback.
    @Inject
    @ConfigProperty(name = "sendium.dlr.storage.backend", defaultValue = "postgresql")
    String selectedBackend = "postgresql";

    @Inject
    @ConfigProperty(name = "sendium.dlr.storage.file.path")
    Optional<String> filePath = Optional.empty();

    @Inject
    @ConfigProperty(name = "sendium.dlr.storage.file.failed-retention", defaultValue = "7D")
    Duration fileFailedRetention = Duration.ofDays(7);

    @Inject
    @ConfigProperty(name = "sendium.dlr.storage.memory.max-tracked-submissions", defaultValue = "100000")
    int memoryMaximum = 100_000;

    @Inject
    @ConfigProperty(name = "sendium.dlr.storage.memory.provider-correlation-ttl", defaultValue = "3D")
    Duration memoryCorrelationTtl = Duration.ofDays(3);

    @Inject
    @ConfigProperty(name = "sendium.dlr.storage.memory.pending-delivery-ttl", defaultValue = "7D")
    Duration memoryDeliveryTtl = Duration.ofDays(7);

    @Inject
    @ConfigProperty(name = "sendium.dlr.storage.memory.cleanup-interval", defaultValue = "1M")
    Duration memoryCleanupInterval = Duration.ofMinutes(1);

    @Inject
    @ConfigProperty(name = "sendium.message.pending.backend", defaultValue = "memory")
    String pendingBackend = "memory";

    @Inject
    @ConfigProperty(name = "sendium.message.pending.path")
    Optional<String> pendingPath = Optional.empty();

    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    private DlrStorageBackend delegate;
    private MemoryDlrReservations memoryReservations;

    @PostConstruct
    void initialize() {
        boolean postgresqlActive = postgresqlDataSource.getHandle().getBean().isActive();
        delegate = switch (selectedBackend) {
            case "postgresql" -> {
                if (!postgresqlActive || !flywayActive || !flywayMigrateAtStart) {
                    throw new IllegalStateException(
                            "PostgreSQL DLR storage requires the active 'dlr' datasource and Flyway migration");
                }
                yield new PostgresqlDlrStorage(postgresqlDataSource.get(), deliveryClaimDuration);
            }
            case "file" -> {
                if (postgresqlActive || flywayActive || flywayMigrateAtStart) {
                    throw new IllegalStateException(
                            "dlrStorage|error|backend:file|reason:postgresqlDatasourceActive");
                }
                yield openFile();
            }
            case "memory", "disabled" -> {
                if (postgresqlActive || flywayActive || flywayMigrateAtStart) {
                    throw new IllegalStateException("dlrStorage|error|backend:" + selectedBackend +
                            "|reason:postgresqlDatasourceActive");
                }
                if ("disabled".equals(selectedBackend)) {
                    yield new DisabledBackend();
                }
                validateMemoryDuration(memoryCorrelationTtl);
                validateMemoryDuration(memoryDeliveryTtl);
                validateMemoryDuration(memoryCleanupInterval);
                validateMemoryDuration(deliveryClaimDuration);
                memoryReservations = new MemoryDlrReservations(memoryMaximum);
                yield new MemoryDlrStorage(memoryReservations, memoryCorrelationTtl, memoryDeliveryTtl,
                        deliveryClaimDuration, Clock.systemUTC());
            }
            default -> throw new IllegalStateException("dlrStorage|error|backend:" +
                    MessageTrace.value(selectedBackend) + "|reason:unknownBackend");
        };

        Gauge.builder("sendium.dlr.storage.selected", this, ignored -> 1.0)
                .description("Active Sendium DLR storage backend")
                .tag("backend", backend())
                .strongReference(true)
                .register(meterRegistry);
        if (delegate instanceof MemoryDlrStorage) {
            Gauge.builder("sendium.dlr.storage.capacity.used", memoryReservations, MemoryDlrReservations::usedCapacity)
                    .description("Reserved DLR ingress submissions, including each accepted multipart part")
                    .tag("backend", "memory").register(meterRegistry);
            Gauge.builder("sendium.dlr.storage.capacity.maximum", memoryReservations, MemoryDlrReservations::maxCapacity)
                    .description("Maximum tracked DLR ingress submissions").tag("backend", "memory").register(meterRegistry);
            logger.warn("dlrStorage|selected|backend:memory|durability:processLocal|replicas:singleProcessOnly" +
                            "|maxTrackedSubmissions:{}|restart:receiptStateLost|recovery:acceptedReservationsFirst",
                    memoryMaximum);
        }
    }

    private static void validateMemoryDuration(Duration duration) {
        try {
            if (duration == null || duration.isNegative() || duration.toMillis() < 1) {
                throw new IllegalArgumentException("dlrStorage|error|backend:memory|reason:invalidDuration");
            }
            Math.addExact(System.currentTimeMillis(), duration.toMillis());
            duration.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("dlrStorage|error|backend:memory|reason:durationOverflow", overflow);
        }
    }

    public OutboundDlrPolicy outboundDlrPolicy() {
        if (delegate == null) {
            throw new IllegalStateException("dlrPolicy|error|reason:storageNotInitialized");
        }
        return memoryReservations != null ? memoryReservations :
                isProcessingDisabled() ? OutboundDlrPolicy.DISABLED : OutboundDlrPolicy.UNRESTRICTED;
    }

    public boolean isProcessingDisabled() {
        return delegate instanceof DisabledBackend;
    }

    @Scheduled(every = "${sendium.dlr.storage.memory.cleanup-interval:1M}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void cleanupMemory() {
        if (delegate instanceof MemoryDlrStorage memory) {
            memory.cleanup(1_000);
        }
    }

    private FileDlrStorage openFile() {
        String configured = filePath.map(String::strip).filter(path -> !path.isEmpty())
                .orElseThrow(() -> new IllegalStateException("dlrStorage|error|backend:file|reason:missingPath"));
        // Stored with millisecond precision; a positive sub-millisecond value would become zero.
        if (fileFailedRetention == null || fileFailedRetention.isNegative() || fileFailedRetention.toMillis() < 1) {
            throw new IllegalStateException("dlrStorage|error|backend:file|reason:invalidFailedRetention");
        }
        Path directory = canonical(Path.of(configured));
        // The DLR namespace is owned independently; it must never share or nest with the SMS pending spool.
        if ("file".equals(pendingBackend) && pendingPath.isPresent() && !pendingPath.get().isBlank()) {
            Path pending = canonical(Path.of(pendingPath.get().strip()));
            if (directory.startsWith(pending) || pending.startsWith(directory)) {
                throw new IllegalStateException("dlrStorage|error|backend:file|reason:overlapsPendingPath");
            }
        }
        FileDlrStorage file = FileDlrStorage.open(directory, deliveryClaimDuration, fileFailedRetention,
                Clock.systemUTC());
        logger.info("dlrStorage|selected|backend:file|path:{}|failedRetention:{}",
                MessageTrace.value(directory.toString()), fileFailedRetention);
        return file;
    }

    // Resolves symbolic links in the nearest existing ancestor, so a not-yet-created path below a linked parent is
    // compared by its physical location. A dangling link is not skipped: toRealPath fails and startup is refused.
    private static Path canonical(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
        } catch (IOException e) {
            throw new IllegalStateException("dlrStorage|error|backend:file|reason:unresolvablePath", e);
        }
    }

    String backend() {
        return delegate == null ? selectedBackend : delegate.backend();
    }

    Optional<DlrStorageBackend.ReceiptCounts> readMetricSnapshot(int maxRows, Duration timeout) {
        return delegate.readMetricSnapshot(maxRows, timeout);
    }

    void verifyAvailable() {
        delegate.verifyAvailable();
    }

    @PreDestroy
    void close() {
        if (delegate != null) {
            delegate.close();
        }
    }

    @Override
    public void recordProviderAccepted(MessageState state, String providerName, String providerMessageId) {
        timed("record_provider_accepted", () -> delegate.recordProviderAccepted(
                state, providerName, providerMessageId));
    }

    public List<ProviderDlrEvent.Result> applyProviderBatch(List<ProviderDlrEvent> events) {
        List<ProviderDlrEvent> batch = List.copyOf(events);
        List<ProviderDlrEvent.Result> results = timed("provider_batch", () -> delegate.applyProviderBatch(batch));
        if (results.size() != batch.size()) {
            throw new IllegalStateException("Provider batch result count mismatch");
        }
        for (int index = 0; index < batch.size(); index++) {
            if (batch.get(index) instanceof ProviderDlrEvent.Receipt receipt &&
                    results.get(index).failure() == null && results.get(index).state().isPresent()) {
                recordOutcome(receipt.providerName(), receipt.dlrState());
            }
        }
        return results;
    }

    @Override
    public Optional<MessageState> recordProviderRejected(MessageState state, String providerName,
                                                          String providerMessageId, int dlrState,
                                                          String errorCode) {
        return timed("record_provider_rejected", () -> delegate.recordProviderRejected(
                state, providerName, providerMessageId, dlrState, errorCode));
    }

    @Override
    public Optional<MessageState> resolveDlr(String providerName, String providerMessageId,
                                             MessageState.MessageStatus status, int dlrState, String errorCode) {
        Optional<MessageState> resolved = timed("resolve", () -> delegate.resolveDlr(
                providerName, providerMessageId, status, dlrState, errorCode));
        if (resolved.isPresent()) {
            recordOutcome(providerName, dlrState);
        }
        return resolved;
    }

    private void recordOutcome(String provider, int dlrState) {
        String outcome = switch (dlrState) {
            case StandardMessage.DLR_STAT_DELIVRD, StandardMessage.DLR_STAT_SEEN -> "delivered";
            case StandardMessage.DLR_STAT_EXPIRED -> "expired";
            default -> "failed";
        };
        meterRegistry.counter("sendium.dlr.outcomes", "provider", provider, "outcome", outcome).increment();
    }

    @Override
    public Optional<MessageState> getState(String gatewayMsgId) {
        return timed("get_state", () -> delegate.getState(gatewayMsgId));
    }

    @Override
    public List<MessageState> listPendingSmppDeliveries(String systemId) {
        return timed("list_pending_smpp", () -> delegate.listPendingSmppDeliveries(systemId));
    }

    @Override
    public List<MessageState> claimDueHttpDeliveries(int limit) {
        return timed("claim_due_http", () -> delegate.claimDueHttpDeliveries(limit));
    }

    @Override
    public Optional<MessageState> startDeliveryAttempt(String gatewayMsgId,
                                                       MessageState.DeliveryChannel expectedChannel) {
        return timed("start_delivery", () -> delegate.startDeliveryAttempt(gatewayMsgId, expectedChannel));
    }

    @Override
    public boolean completeDelivery(String gatewayMsgId, int expectedAttempt) {
        return timed("complete_delivery", () -> delegate.completeDelivery(gatewayMsgId, expectedAttempt));
    }

    @Override
    public boolean retryDelivery(String gatewayMsgId, int expectedAttempt, String result, long nextAttemptAt) {
        return timed("retry_delivery", () -> delegate.retryDelivery(
                gatewayMsgId, expectedAttempt, result, nextAttemptAt));
    }

    @Override
    public boolean failDelivery(String gatewayMsgId, int expectedAttempt, String result) {
        return timed("fail_delivery", () -> delegate.failDelivery(gatewayMsgId, expectedAttempt, result));
    }

    @Override
    public boolean failInvalidDelivery(String gatewayMsgId, int expectedAttempt, String result) {
        return timed("fail_invalid_delivery", () -> delegate.failInvalidDelivery(
                gatewayMsgId, expectedAttempt, result));
    }

    private <T> T timed(String operation, Supplier<T> action) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            T result = action.get();
            sample.stop(timer(operation, "success"));
            return result;
        } catch (RuntimeException | Error e) {
            sample.stop(timer(operation, "error"));
            throw e;
        }
    }

    private void timed(String operation, Runnable action) {
        timed(operation, () -> {
            action.run();
            return null;
        });
    }

    private Timer timer(String operation, String outcome) {
        return timers.computeIfAbsent(operation + '/' + outcome, ignored -> Timer.builder(METRIC_NAME)
                .description("Sendium DLR storage operation latency")
                .tags("backend", backend(), "operation", operation, "outcome", outcome)
                .register(meterRegistry));
    }

    /** Intentional disabled handling has no correlation/delivery state and never acknowledges a write. */
    private static final class DisabledBackend implements DlrStorageBackend {
        private volatile boolean closed;

        @Override
        public String backend() {
            return "disabled";
        }

        @Override
        public void verifyAvailable() {
            if (closed) {
                throw new DlrStorageException("dlrStorage|error|backend:disabled|reason:closed");
            }
        }

        @Override
        public void close() {
            closed = true;
        }

        private DlrStorageException writeRejected() {
            return new DlrStorageException("dlrStorage|error|backend:disabled|reason:writeRejected");
        }

        @Override
        public void recordProviderAccepted(MessageState state, String provider, String id) {
            throw writeRejected();
        }

        @Override
        public Optional<MessageState> recordProviderRejected(MessageState state, String provider, String id, int status, String error) {
            throw writeRejected();
        }

        @Override
        public List<ProviderDlrEvent.Result> applyProviderBatch(List<ProviderDlrEvent> events) {
            throw writeRejected();
        }

        @Override
        public Optional<MessageState> resolveDlr(String provider, String id, MessageState.MessageStatus status, int dlrState, String error) {
            return Optional.empty();
        }

        @Override
        public Optional<MessageState> getState(String id) {
            return Optional.empty();
        }

        @Override
        public List<MessageState> listPendingSmppDeliveries(String system) {
            return List.of();
        }

        @Override
        public List<MessageState> claimDueHttpDeliveries(int limit) {
            return List.of();
        }

        @Override
        public Optional<MessageState> startDeliveryAttempt(String id, MessageState.DeliveryChannel channel) {
            return Optional.empty();
        }

        @Override
        public boolean completeDelivery(String id, int attempt) {
            throw writeRejected();
        }

        @Override
        public boolean retryDelivery(String id, int attempt, String result, long next) {
            throw writeRejected();
        }

        @Override
        public boolean failDelivery(String id, int attempt, String result) {
            throw writeRejected();
        }

        @Override
        public boolean failInvalidDelivery(String id, int attempt, String result) {
            throw writeRejected();
        }

        @Override
        public Optional<ReceiptCounts> readMetricSnapshot(int maxRows, Duration timeout) {
            return Optional.empty();
        }
    }
}
