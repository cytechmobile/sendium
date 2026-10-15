package gr.cytech.sendium.performance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Absolute-time, open-loop scheduling; completed responses never pace the next target. */
public final class FixedRateGenerator implements AutoCloseable {
    @FunctionalInterface
    public interface Target {
        /** Must return promptly; false means the adapter/ledger counted a capacity-missed target. */
        boolean submit(boolean measured, long scheduledNanos);
    }

    public record Statistics(double requestedPerSecond, long scheduled, long attempted,
            long missedScheduling, long missedCapacity, long elapsedNanos,
            long maxLatenessNanos, boolean stoppedEarly) {
        public Double achievedInputPerSecond() {
            return elapsedNanos == 0 ? null : attempted * 1_000_000_000.0 / elapsedNanos;
        }
        public boolean saturated() { return missedScheduling > 0 || missedCapacity > 0; }
    }

    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(20);
    private final double rate;
    private final double periodNanos;
    private final MessageLedger ledger;
    private final Target target;
    private final Lifecycle.Clock clock;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("fixed-rate-generator").factory());
    private Run active;
    private boolean closed;

    private static final class Run {
        final AtomicBoolean stopped = new AtomicBoolean();
        final CompletableFuture<Statistics> completion = new CompletableFuture<>();
        volatile Thread thread;
    }

    public FixedRateGenerator(RunConfig config, MessageLedger ledger, Target target) {
        this(config, ledger, target, new Lifecycle.Clock() {
            public long nanoTime() { return System.nanoTime(); }
            public void sleepNanos(long nanos) throws InterruptedException { TimeUnit.NANOSECONDS.sleep(nanos); }
        });
    }

    FixedRateGenerator(RunConfig config, MessageLedger ledger, Target target, Lifecycle.Clock clock) {
        rate = config.offeredMessagesPerSecond();
        if (rate > 1_000_000_000) { throw new IllegalArgumentException("Rate exceeds nanosecond schedule resolution"); }
        periodNanos = 1_000_000_000.0 / rate;
        this.ledger = ledger;
        this.target = target;
        this.clock = clock;
    }

    public synchronized CompletableFuture<Statistics> start(Lifecycle.Phase phase, long startNanos, long deadlineNanos) {
        if (closed || active != null && !active.completion.isDone()) {
            throw new IllegalStateException("Generator is closed or a phase is already running");
        }
        validatePhase(phase, startNanos, deadlineNanos);
        Run run = new Run();
        active = run;
        executor.execute(() -> {
            synchronized (run) { run.thread = Thread.currentThread(); }
            Statistics statistics = null;
            Throwable failure = null;
            try { statistics = generate(phase, startNanos, deadlineNanos, run.stopped); }
            catch (Throwable exception) { failure = exception; }
            finally { synchronized (run) { run.thread = null; Thread.interrupted(); } }
            if (failure == null) { run.completion.complete(statistics); }
            else { run.completion.completeExceptionally(failure); }
        });
        return run.completion.copy();
    }

    /** Wake a sleeping phase and join it before switching cohort or freezing accounting. */
    public Statistics stop() throws Exception {
        Run run;
        synchronized (this) { run = active; }
        if (run == null) { return null; }
        run.stopped.set(true);
        synchronized (run) { if (run.thread != null) { run.thread.interrupt(); } }
        return run.completion.get(5, TimeUnit.SECONDS);
    }

    Statistics generate(Lifecycle.Phase phase, long start, long deadline, AtomicBoolean stopped) throws Exception {
        validatePhase(phase, start, deadline);
        boolean measured = phase == Lifecycle.Phase.MEASUREMENT;
        long duration = deadline - start;
        // Exact decimal count at setup avoids spurious extra slots for fractional rates (e.g. 0.7/s).
        long total = BigDecimal.valueOf(rate).multiply(BigDecimal.valueOf(duration))
                .divide(BigDecimal.valueOf(1_000_000_000L), 0, RoundingMode.CEILING).longValueExact();
        long index = 0, attempted = 0, missedScheduling = 0, missedCapacity = 0, maxLateness = 0;
        while (index < total && !stopped.get()) {
            long elapsed = clock.nanoTime() - start;
            if (elapsed >= duration) { break; }
            long offset = offset(index);
            if (elapsed < offset) {
                pause(Math.min(POLL_NANOS, offset - elapsed), stopped);
                continue;
            }
            long latestDue = Math.min(total - 1, (long) Math.floor(elapsed / periodNanos));
            if (latestDue > index) {
                long skipped = latestDue - index;
                ledger.missedTargets(measured, skipped);
                missedScheduling += skipped;
                index = latestDue;
                offset = offset(index);
                if (elapsed < offset) { continue; }
            }
            maxLateness = Math.max(maxLateness, elapsed - offset);
            if (target.submit(measured, start + offset)) { attempted++; }
            else { missedCapacity++; }
            index++;
        }
        long unattempted = total - index;
        if (unattempted > 0) {
            ledger.missedTargets(measured, unattempted);
            missedScheduling += unattempted;
        }
        // The observation window includes the quiet tail after the final scheduled slot.
        while (!stopped.get() && clock.nanoTime() - start < duration) {
            pause(Math.max(0, Math.min(POLL_NANOS, duration - (clock.nanoTime() - start))), stopped);
        }
        long elapsed = Math.max(0, clock.nanoTime() - start);
        return new Statistics(rate, total, attempted, missedScheduling, missedCapacity,
                Math.min(elapsed, duration), maxLateness, stopped.get() && elapsed < duration);
    }

    private long offset(long index) { return (long) Math.ceil(index * periodNanos); }

    private void pause(long nanos, AtomicBoolean stopped) throws InterruptedException {
        try { clock.sleepNanos(nanos); }
        catch (InterruptedException exception) { if (!stopped.get()) { throw exception; } }
    }

    private static void validatePhase(Lifecycle.Phase phase, long start, long deadline) {
        if (phase != Lifecycle.Phase.WARMUP && phase != Lifecycle.Phase.MEASUREMENT || deadline - start < 0) {
            throw new IllegalArgumentException("Invalid traffic phase or duration");
        }
    }

    @Override public void close() throws Exception {
        synchronized (this) { if (closed) { return; } closed = true; }
        try { stop(); }
        finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Traffic generator did not stop");
            }
        }
    }
}
