package gr.cytech.sendium.core.worker;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
@Startup
@IfBuildProperty(name = "sendium.dlr.persistence.enabled", stringValue = "true", enableIfMissing = false)
public class ProviderDlrProcessor implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ProviderDlrProcessor.class);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

    private final ManagedDlrStorage storage;
    private final ArrayBlockingQueue<Pending> queue;
    private final Semaphore capacity;
    private final int batchSize;
    private final long delayNanos;
    private final Duration shutdownTimeout;
    private final Object admission = new Object();
    private final Counter rejected;
    private final DistributionSummary batchEvents;
    private final ExecutorService completions = Executors.newVirtualThreadPerTaskExecutor();
    private final Thread worker;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean accepting = true;
    private volatile boolean aborted;
    private volatile List<Pending> inFlight = List.of();

    @Inject
    public ProviderDlrProcessor(ManagedDlrStorage storage, MeterRegistry metrics,
            @ConfigProperty(name = "sendium.dlr.batch.size", defaultValue = "100") int batchSize,
            @ConfigProperty(name = "sendium.dlr.batch.max-delay", defaultValue = "500ms") Duration delay,
            @ConfigProperty(name = "sendium.dlr.batch.queue-capacity", defaultValue = "10000") int queueCapacity) {
        this(storage, metrics, batchSize, delay, queueCapacity, SHUTDOWN_TIMEOUT);
    }

    ProviderDlrProcessor(ManagedDlrStorage storage, MeterRegistry metrics, int batchSize,
                         Duration delay, int queueCapacity, Duration shutdownTimeout) {
        if (batchSize < 1 || batchSize > 1000 || queueCapacity < 1 || delay.isNegative() || delay.isZero() ||
                shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
            throw new IllegalArgumentException("Invalid provider DLR batch settings");
        }
        this.storage = storage;
        this.batchSize = batchSize;
        this.delayNanos = delay.toNanos();
        this.shutdownTimeout = shutdownTimeout;
        queue = new ArrayBlockingQueue<>(queueCapacity);
        capacity = new Semaphore(queueCapacity);
        rejected = metrics.counter("sendium.dlr.batch.rejected");
        batchEvents = metrics.summary("sendium.dlr.batch.events");
        Gauge.builder("sendium.dlr.batch.pending", capacity, slots -> queueCapacity - slots.availablePermits())
                .register(metrics);
        worker = Thread.ofPlatform().daemon(true).name("sendium-dlr-persistence").start(this::run);
    }

    public CompletionStage<Optional<MessageState>> submit(ProviderDlrEvent event) {
        java.util.Objects.requireNonNull(event, "event");
        synchronized (admission) {
            if (!accepting || !capacity.tryAcquire()) {
                rejected.increment();
                return CompletableFuture.failedFuture(new DlrStorageException("Provider DLR queue full or stopping"));
            }
            var future = new CompletableFuture<Optional<MessageState>>();
            queue.add(new Pending(event, System.nanoTime(), future, new AtomicBoolean()));
            admission.notifyAll();
            return future;
        }
    }

    private void run() {
        try {
            while (!aborted && (accepting || !queue.isEmpty())) {
                List<Pending> batch = new ArrayList<>(batchSize);
                try {
                    Pending first = next(TimeUnit.MILLISECONDS.toNanos(100));
                    if (first == null) {
                        continue;
                    }
                    batch.add(first);
                    long deadline = first.enqueuedAt() + delayNanos;
                    while (batch.size() < batchSize) {
                        long remaining = deadline - System.nanoTime();
                        Pending next = remaining > 0 ? next(remaining) : queue.poll();
                        if (next == null) {
                            break;
                        }
                        batch.add(next);
                    }
                } catch (InterruptedException e) {
                    // A forced shutdown must never acknowledge a batch that has not committed.
                }
                if (!batch.isEmpty()) {
                    persist(List.copyOf(batch));
                }
            }
        } finally {
            failQueued(new DlrStorageException("Provider DLR processor stopped before persistence"));
        }
    }

    private Pending next(long timeoutNanos) throws InterruptedException {
        synchronized (admission) {
            Pending pending = queue.poll();
            long deadline = System.nanoTime() + timeoutNanos;
            while (pending == null && accepting && !aborted && timeoutNanos > 0) {
                TimeUnit.NANOSECONDS.timedWait(admission, timeoutNanos);
                pending = queue.poll();
                timeoutNanos = deadline - System.nanoTime();
            }
            return pending;
        }
    }

    private void persist(List<Pending> batch) {
        inFlight = batch;
        try {
            if (aborted) {
                throw new DlrStorageException("Provider DLR shutdown timed out");
            }
            batchEvents.record(batch.size());
            List<ProviderDlrEvent.Result> results = storage.applyProviderBatch(batch.stream().map(Pending::event).toList());
            if (results.size() != batch.size()) {
                throw new DlrStorageException("Provider batch result count mismatch");
            }
            for (int i = 0; i < batch.size(); i++) {
                Pending pending = batch.get(i);
                ProviderDlrEvent.Result result = results.get(i);
                complete(pending, result.state(), result.failure());
            }
        } catch (Exception e) {
            logger.error("Provider DLR batch persistence failed; events={}", batch.size(), e);
            for (Pending pending : batch) {
                complete(pending, Optional.empty(), e);
            }
        } finally {
            inFlight = List.of();
        }
    }

    private void complete(Pending pending, Optional<MessageState> state, Throwable failure) {
        if (!pending.completed().compareAndSet(false, true)) {
            return;
        }
        // Completion can write SMPP responses; keep that I/O off the persistence worker too.
        try {
            completions.execute(() -> {
                try {
                    if (failure != null || aborted) {
                        pending.result().completeExceptionally(failure != null ? failure : new DlrStorageException("Provider DLR shutdown timed out"));
                    } else {
                        pending.result().complete(state);
                    }
                } finally {
                    capacity.release();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            pending.result().completeExceptionally(e);
            capacity.release();
        }
    }

    private void failQueued(DlrStorageException failure) {
        Pending pending;
        while ((pending = queue.poll()) != null) {
            complete(pending, Optional.empty(), failure);
        }
    }

    @Override
    @PreDestroy
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (admission) {
            accepting = false;
            admission.notifyAll();
        }
        try {
            if (!worker.join(shutdownTimeout)) {
                abort();
            }
            completions.shutdown();
            if (!completions.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                completions.shutdownNow();
            }
        } catch (InterruptedException e) {
            abort();
            completions.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void abort() {
        aborted = true;
        var failure = new DlrStorageException("Provider DLR shutdown timed out");
        for (Pending pending : inFlight) {
            complete(pending, Optional.empty(), failure);
        }
        failQueued(failure);
        worker.interrupt();
    }

    private record Pending(ProviderDlrEvent event, long enqueuedAt,
                           CompletableFuture<Optional<MessageState>> result, AtomicBoolean completed) { }
}
