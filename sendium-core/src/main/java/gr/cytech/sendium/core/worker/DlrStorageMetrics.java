package gr.cytech.sendium.core.worker;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToDoubleFunction;

/** Best-effort bounded snapshots; scrapes only read the last immutable collection result. */
@Startup
@ApplicationScoped
@IfBuildProperty(name = "sendium.dlr.persistence.enabled", stringValue = "true", enableIfMissing = false)
public class DlrStorageMetrics {
    @Inject ManagedDlrStorage storage;
    @Inject MeterRegistry metrics;

    @Inject
    @ConfigProperty(name = "sendium.metrics.storage.enabled", defaultValue = "true")
    boolean enabled = true;

    @Inject
    @ConfigProperty(name = "sendium.metrics.storage.interval", defaultValue = "60s")
    Duration interval = Duration.ofSeconds(60);

    @Inject
    @ConfigProperty(name = "sendium.metrics.storage.timeout", defaultValue = "2s")
    Duration timeout = Duration.ofSeconds(2);

    @Inject
    @ConfigProperty(name = "sendium.metrics.storage.max-rows", defaultValue = "100000")
    int maxRows = 100000;

    private long staleAfterNanos;
    private final AtomicBoolean collecting = new AtomicBoolean();
    private final List<Meter> ownedMeters = new ArrayList<>();
    private volatile Snapshot snapshot = new Snapshot(null, 0, Double.NaN);

    @PostConstruct
    void initialize() {
        if (interval.compareTo(Duration.ofSeconds(1)) < 0 || interval.compareTo(Duration.ofNanos(Long.MAX_VALUE / 2)) > 0 ||
                timeout.getNano() != 0 || timeout.getSeconds() < 1 || timeout.getSeconds() > Integer.MAX_VALUE ||
                maxRows < 1 || maxRows == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid storage metrics interval, whole-second timeout or row budget");
        }
        staleAfterNanos = interval.toNanos() * 2;
        register("sendium.storage.collection.enabled", "Whether receipt storage snapshot collection is enabled", ignored -> enabled ? 1 : 0);
        register("sendium.storage.collection.available", "Whether a complete fresh receipt snapshot is available", ignored -> available(snapshot) ? 1 : 0);
        register("sendium.storage.collection.last.success.timestamp.seconds", "Unix timestamp of last complete receipt snapshot",
                ignored -> snapshot.timestamp());
        for (String state : new String[]{"waiting_provider", "pending_forward", "failed_forward"}) {
            ownedMeters.add(Gauge.builder("sendium.dlr.storage.messages", this, collector -> collector.occupancy(state))
                    .description("Complete receipt storage snapshot; NaN when unavailable")
                    .tags("backend", "postgresql", "state", state).register(metrics));
        }
    }

    private void register(String name, String description, ToDoubleFunction<DlrStorageMetrics> value) {
        ownedMeters.add(Gauge.builder(name, this, value).description(description)
                .tags("backend", "postgresql", "stage", "dlr").register(metrics));
    }

    @PreDestroy
    void unregister() {
        ownedMeters.forEach(metrics::remove);
        ownedMeters.clear();
    }

    @Scheduled(every = "${sendium.metrics.storage.interval:60s}", delayed = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void collect() {
        if (!enabled || !collecting.compareAndSet(false, true)) {
            return;
        }
        try {
            var counts = storage.readMetricSnapshot(maxRows, timeout);
            if (counts.isPresent()) {
                var clock = metrics.config().clock();
                snapshot = new Snapshot(counts.orElseThrow(), clock.monotonicTime(), clock.wallTime() / 1000.0);
                return;
            }
            snapshot = new Snapshot(null, 0, snapshot.timestamp());
        } catch (RuntimeException failure) {
            // Monitoring is deliberately independent of readiness and normal receipt processing.
            snapshot = new Snapshot(null, 0, snapshot.timestamp());
        } finally {
            collecting.set(false);
        }
    }

    private boolean available(Snapshot value) {
        return enabled && value.counts() != null &&
                metrics.config().clock().monotonicTime() - value.collectedAt() < staleAfterNanos;
    }

    private double occupancy(String state) {
        Snapshot value = snapshot;
        if (!available(value)) {
            return Double.NaN;
        }
        return switch (state) {
            case "waiting_provider" -> value.counts().waitingProvider();
            case "pending_forward" -> value.counts().pendingReturn();
            case "failed_forward" -> value.counts().failedReturn();
            default -> throw new IllegalArgumentException("Unknown receipt metric state");
        };
    }

    private record Snapshot(PostgresqlDlrStorage.ReceiptCounts counts, long collectedAt, double timestamp) { }
}
