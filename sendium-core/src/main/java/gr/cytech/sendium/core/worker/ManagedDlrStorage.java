package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.util.MessageTrace;
import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.runtime.Startup;
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
    @ConfigProperty(name = "sendium.message.pending.backend", defaultValue = "memory")
    String pendingBackend = "memory";

    @Inject
    @ConfigProperty(name = "sendium.message.pending.path")
    Optional<String> pendingPath = Optional.empty();

    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    private DlrStorageBackend delegate;

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
            case "memory" -> throw new IllegalStateException("dlrStorage|error|backend:memory|reason:unsupported");
            default -> throw new IllegalStateException("dlrStorage|error|backend:" +
                    MessageTrace.value(selectedBackend) + "|reason:unknownBackend");
        };

        Gauge.builder("sendium.dlr.storage.selected", this, ignored -> 1.0)
                .description("Active Sendium DLR storage backend")
                .tag("backend", backend())
                .strongReference(true)
                .register(meterRegistry);
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
        return delegate.backend();
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
}
