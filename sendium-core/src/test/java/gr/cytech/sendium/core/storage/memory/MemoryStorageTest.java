package gr.cytech.sendium.core.storage.memory;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static gr.cytech.sendium.core.storage.OutboundStage.Role.PENDING;
import static gr.cytech.sendium.core.storage.OutboundStage.Role.ROUTER_QUEUE;
import static gr.cytech.sendium.core.storage.OutboundStage.State.CLOSED;
import static gr.cytech.sendium.core.storage.OutboundStage.State.NEW;
import static gr.cytech.sendium.core.storage.OutboundStage.State.READY;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.CAPACITY_EXCEEDED;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.INVALID_TRANSITION;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.OWNERSHIP_CONFLICT;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNSUPPORTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(10)
class MemoryStorageTest {
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void lifecycleIsExplicitAndOnlyOneSelectorCanOwnPendingState() {
        var pending = new MemoryPendingMessageStore<TestMessage>(2, TestMessage::copy);
        var router = new MemorySelectedRouterStore<>(pending, 1, CLOCK);
        var other = new MemorySelectedRouterStore<>(pending, 1, CLOCK);
        assertThat(pending.status()).isEqualTo(new OutboundStage.Status("memory", false, NEW));
        fails(router::open, ROUTER_QUEUE, UNAVAILABLE);
        fails(() -> pending.admit(source(), message("one", 1)), PENDING, UNAVAILABLE);
        pending.open();
        pending.open();
        router.open();
        router.open();
        assertThat(router.status()).isEqualTo(new OutboundStage.Status("memory", false, READY));
        fails(other::open, ROUTER_QUEUE, OWNERSHIP_CONFLICT);
        router.close();
        router.close();
        fails(other::open, ROUTER_QUEUE, OWNERSHIP_CONFLICT);
        fails(router::open, ROUTER_QUEUE, INVALID_TRANSITION);
        pending.close();
        pending.close();
        fails(pending::open, PENDING, INVALID_TRANSITION);
        fails(() -> pending.find(source()), PENDING, UNAVAILABLE);
        fails(() -> router.take(Duration.ZERO), ROUTER_QUEUE, UNAVAILABLE);
        assertThat(pending.status().state()).isEqualTo(CLOSED);
        assertThat(router.status().state()).isEqualTo(CLOSED);
    }

    @Test
    void duplicateAdmissionDoesNotOverwriteAcceptedStateOrConsumeCapacity() {
        try (var stores = new Stores(1, 1)) {
            SourceId source = source();
            TestMessage input = message("accepted", 2);
            input.tags.add("original");
            stores.pending.admit(source, input);
            input.body = "changed";
            input.tags.clear();
            stores.pending.admit(source, message("duplicate", 3));
            TestMessage firstRead = stores.pending.find(source).orElseThrow();
            assertThat(firstRead.body).isEqualTo("accepted");
            assertThat(firstRead.tags).containsExactly("original");
            firstRead.tags.clear();
            assertThat(stores.pending.find(source).orElseThrow().tags).containsExactly("original");
            fails(() -> stores.pending.admit(source(), input), PENDING, CAPACITY_EXCEEDED);
        }
    }

    @Test
    void selectionHonorsDueTimesAndPriorityWithoutFutureWorkBlockingReadyMessages() throws Exception {
        try (var stores = new Stores(5, 5)) {
            stores.pending.admit(source(), new TestMessage("future-high", 3, NOW.plusSeconds(10)));
            stores.pending.admit(source(), message("low", 1));
            stores.pending.admit(source(), message("high-first", 3));
            stores.pending.admit(source(), message("normal", 2));
            stores.pending.admit(source(), message("high-second", 3));
            assertThat(stores.router.selectAndStage(5)).isEqualTo(4);
            List<String> bodies = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                bodies.add(stores.router.take(Duration.ZERO).orElseThrow().message().body);
            }
            assertThat(bodies).containsExactly("high-first", "high-second", "normal", "low");
            assertThat(stores.router.selectAndStage(5)).isZero();
        }
    }

    @Test
    void advancingTheClockMakesDeferredWorkEligible() throws Exception {
        AtomicReference<Instant> time = new AtomicReference<>(NOW);
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return time.get(); }
        };
        try (var pending = new MemoryPendingMessageStore<TestMessage>(1, TestMessage::copy, msg -> msg.available);
             var router = new MemorySelectedRouterStore<>(pending, 1, clock)) {
            pending.open();
            router.open();
            TestMessage delayed = new TestMessage("delayed", 2, NOW.plusSeconds(1));
            pending.admit(source(), delayed);
            delayed.available = NOW;
            assertThat(router.selectAndStage(1)).isZero();
            time.set(NOW.plusSeconds(1));
            assertThat(router.selectAndStage(1)).isEqualTo(1);
            assertThat(router.take(Duration.ZERO).orElseThrow().message().body).isEqualTo("delayed");
        }
    }

    @Test
    void refillCopiesOnlyItsBudgetAndReservesCapacityForTakenWork() throws Exception {
        AtomicInteger copies = new AtomicInteger();
        try (var stores = new Stores(100, 2, msg -> {
            copies.incrementAndGet();
            return msg.copy();
        })) {
            for (int i = 0; i < 100; i++) {
                stores.pending.admit(source(), message("item-" + i, 2));
            }
            copies.set(0);
            assertThat(stores.router.selectAndStage(1)).isEqualTo(1);
            assertThat(copies).hasValue(1);
            assertThat(stores.router.selectAndStage(50)).isEqualTo(1);
            assertThat(copies).hasValue(2);
            Selected<TestMessage> first = stores.router.take(Duration.ZERO).orElseThrow();
            Selected<TestMessage> second = stores.router.take(Duration.ZERO).orElseThrow();
            assertThat(stores.router.selectAndStage(50)).isZero();
            assertThat(copies).hasValue(4);
            stores.router.release(first);
            stores.router.release(first);
            assertThat(stores.router.take(Duration.ZERO).orElseThrow().id()).isEqualTo(first.id());
            assertThat(stores.router.take(Duration.ZERO)).isEmpty();
            stores.router.markRouted(second.id());
            assertThat(stores.router.selectAndStage(50)).isEqualTo(1);
        }
    }

    @Test
    void releasePreservesExecutionChangesWithoutMutatingPendingOrReactivatingAStaleTake() throws Exception {
        try (var stores = new Stores(1, 1)) {
            SourceId source = source();
            stores.pending.admit(source, message("accepted", 2));
            stores.router.selectAndStage(1);
            Selected<TestMessage> first = stores.router.take(Duration.ZERO).orElseThrow();
            first.message().body = "filtered";
            first.message().tags.add("filter-tag");
            stores.router.release(first);
            first.message().body = "late mutation";
            first.message().tags.clear();
            Selected<TestMessage> second = stores.router.take(Duration.ZERO).orElseThrow();
            assertThat(second.id()).isEqualTo(first.id());
            assertThat(second.message().body).isEqualTo("filtered");
            assertThat(second.message().tags).containsExactly("filter-tag");
            assertThat(stores.pending.find(source).orElseThrow().body).isEqualTo("accepted");
            assertThat(stores.pending.find(source).orElseThrow().tags).isEmpty();
            stores.router.release(first);
            assertThat(stores.router.take(Duration.ZERO)).isEmpty();
            var forged = new Selected<>(second.id(), Set.of(source()), second.message());
            fails(() -> stores.router.release(forged), ROUTER_QUEUE, INVALID_TRANSITION);
            stores.router.markRouted(second.id());
            fails(() -> stores.router.release(second), ROUTER_QUEUE, INVALID_TRANSITION);
        }
    }

    @Test
    void routingDoesNotCompleteTheSourceAndCleanupIsRetrySafe() throws Exception {
        try (var stores = new Stores(1, 1)) {
            SourceId source = source();
            stores.pending.admit(source, message("one", 2));
            stores.router.selectAndStage(1);
            Selected<TestMessage> selected = stores.router.take(Duration.ZERO).orElseThrow();
            fails(() -> stores.router.complete(selected.id()), ROUTER_QUEUE, INVALID_TRANSITION);
            stores.router.markRouted(selected.id());
            stores.router.markRouted(selected.id());
            assertThat(stores.router.selectAndStage(1)).isZero();
            assertThat(stores.pending.find(source)).isPresent();
            fails(() -> stores.pending.admit(source(), message("two", 2)), PENDING, CAPACITY_EXCEEDED);
            stores.pending.complete(Set.of(source, source()));
            stores.pending.complete(Set.of(source));
            stores.router.complete(selected.id());
            stores.router.complete(selected.id());
            assertThat(stores.pending.find(source)).isEmpty();
            stores.pending.admit(source(), message("two", 2));
            assertThat(stores.router.selectAndStage(1)).isEqualTo(1);
        }
    }

    @Test
    void failedAdmissionDoesNotReserveCapacityAndIdentitySnapshotsAreRejected() {
        AtomicBoolean fail = new AtomicBoolean(true);
        try (var stores = new Stores(1, 1, msg -> {
            if (fail.get()) {
                throw new IllegalStateException("snapshot failure");
            }
            return msg.copy();
        })) {
            SourceId source = source();
            fails(() -> stores.pending.admit(source, message("failed", 2)), PENDING, UNAVAILABLE);
            assertThat(stores.pending.find(source)).isEmpty();
            fail.set(false);
            stores.pending.admit(source(), message("accepted", 2));
        }
        try (var pending = new MemoryPendingMessageStore<TestMessage>(1, UnaryOperator.identity())) {
            pending.open();
            fails(() -> pending.admit(source(), message("unsafe", 2)), PENDING, UNSUPPORTED);
        }
    }

    @Test
    void snapshotsCannotFlattenAnApplicationMessageSubtype() {
        try (var pending = new MemoryPendingMessageStore<StandardMessage>(1, ignored -> new StandardMessage())) {
            pending.open();
            SourceId source = source();
            fails(() -> pending.admit(source, message("custom payload", 2)), PENDING, UNSUPPORTED);
            assertThat(pending.find(source)).isEmpty();
            pending.admit(source(), new StandardMessage());
        }
    }

    @Test
    void failureDuringBatchPublicationRetainsPublishedPrefixAndUnselectedSource() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(false);
        try (var stores = new Stores(2, 2, msg -> {
            if (fail.get() && msg.body.equals("second")) {
                throw new IllegalStateException("snapshot failure");
            }
            return msg.copy();
        })) {
            SourceId first = source();
            SourceId second = source();
            stores.pending.admit(first, message("first", 2));
            stores.pending.admit(second, message("second", 2));
            fail.set(true);
            fails(() -> stores.router.selectAndStage(2), ROUTER_QUEUE, UNAVAILABLE);
            assertThat(stores.router.take(Duration.ZERO).orElseThrow().sources()).containsExactly(first);
            fail.set(false);
            assertThat(stores.pending.find(first)).isPresent();
            assertThat(stores.pending.find(second)).isPresent();
            assertThat(stores.router.selectAndStage(2)).isEqualTo(1);
            assertThat(stores.router.take(Duration.ZERO).orElseThrow().sources()).containsExactly(second);
            assertThat(stores.router.take(Duration.ZERO)).isEmpty();
        }
    }

    @Test
    void snapshotFailuresDuringTakeAndReturnLeaveOwnershipRetryable() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(false);
        try (var stores = new Stores(1, 1, msg -> {
            if (fail.get()) {
                throw new IllegalStateException("snapshot failure");
            }
            return msg.copy();
        })) {
            stores.pending.admit(source(), message("one", 2));
            stores.router.selectAndStage(1);
            fail.set(true);
            fails(() -> stores.router.take(Duration.ZERO), ROUTER_QUEUE, UNAVAILABLE);
            fail.set(false);
            Selected<TestMessage> selected = stores.router.take(Duration.ZERO).orElseThrow();
            selected.message().body = "updated";
            fail.set(true);
            fails(() -> stores.router.release(selected), ROUTER_QUEUE, UNAVAILABLE);
            fail.set(false);
            stores.router.release(selected);
            assertThat(stores.router.take(Duration.ZERO).orElseThrow().message().body).isEqualTo("updated");
            assertThat(stores.router.take(Duration.ZERO)).isEmpty();
        }
    }

    @Test
    void concurrentSelectorsAndTakersNeverSelectTheSameSourceTwice() throws Exception {
        int count = 80;
        try (var stores = new Stores(count, 8); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                stores.pending.admit(source(), message("item-" + i, i % 4));
            }
            Set<SourceId> seen = ConcurrentHashMap.newKeySet();
            Set<SelectionId> selections = ConcurrentHashMap.newKeySet();
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 4; worker++) {
                jobs.add(executor.submit(() -> {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (seen.size() < count && System.nanoTime() < deadline) {
                        stores.router.selectAndStage(3);
                        var selected = stores.router.take(Duration.ZERO);
                        if (selected.isPresent()) {
                            var work = selected.orElseThrow();
                            assertThat(seen.add(work.sources().iterator().next())).isTrue();
                            assertThat(selections.add(work.id())).isTrue();
                            stores.router.markRouted(work.id());
                        }
                    }
                    return null;
                }));
            }
            for (var job : jobs) {
                job.get(6, TimeUnit.SECONDS);
            }
            assertThat(seen).hasSize(count);
            assertThat(stores.router.selectAndStage(count)).isZero();
            for (SourceId source : seen) {
                assertThat(stores.pending.find(source)).isPresent();
            }
            stores.pending.complete(seen);
            selections.forEach(stores.router::complete);
        }
    }

    @Test
    void aWaitingTakeWakesWhenWorkIsStaged() throws Exception {
        try (var stores = new Stores(1, 1); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AtomicReference<Thread> waiter = new AtomicReference<>();
            var result = executor.submit(() -> {
                waiter.set(Thread.currentThread());
                return stores.router.take(Duration.ofSeconds(2));
            });
            awaitWaiting(waiter);
            stores.pending.admit(source(), message("one", 2));
            stores.router.selectAndStage(1);
            assertThat(result.get(1, TimeUnit.SECONDS).orElseThrow().message().body).isEqualTo("one");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void closingEitherStoreWakesWaitingTakes(boolean closePending) throws Exception {
        try (var stores = new Stores(1, 1); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AtomicReference<Thread> waiter = new AtomicReference<>();
            var result = executor.submit(() -> {
                waiter.set(Thread.currentThread());
                return stores.router.take(Duration.ofSeconds(2));
            });
            awaitWaiting(waiter);
            if (closePending) {
                stores.pending.close();
                assertThat(stores.router.status().state()).isEqualTo(OutboundStage.State.FAILED);
            } else {
                stores.router.close();
            }
            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(OutboundStorageException.class);
        }
    }

    @Test
    void takeIsInterruptibleAndFreshInstancesRecoverNothing() throws Exception {
        SourceId source = source();
        try (var stores = new Stores(1, 1)) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch started = new CountDownLatch(1);
            Thread taker = Thread.ofVirtual().start(() -> {
                started.countDown();
                try {
                    stores.router.take(Duration.ofSeconds(2));
                } catch (Throwable caught) {
                    failure.set(caught);
                }
            });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            taker.interrupt();
            taker.join(3000);
            assertThat(taker.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(InterruptedException.class);
            stores.pending.admit(source, message("will be lost", 2));
            stores.router.selectAndStage(1);
        }
        try (var fresh = new Stores(1, 1)) {
            assertThat(fresh.pending.find(source)).isEmpty();
            assertThat(fresh.router.selectAndStage(1)).isZero();
            assertThat(fresh.router.take(Duration.ZERO)).isEmpty();
        }
    }

    @Test
    void invalidLimitsAndTimeoutsDoNotConsumeWork() throws Exception {
        try (var stores = new Stores(1, 1)) {
            stores.pending.admit(source(), message("one", 2));
            assertThatThrownBy(() -> stores.router.selectAndStage(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> stores.router.take(Duration.ofNanos(-1))).isInstanceOf(IllegalArgumentException.class);
            assertThat(stores.router.selectAndStage(1)).isEqualTo(1);
            assertThat(stores.router.take(Duration.ofSeconds(Long.MAX_VALUE))).isPresent();
            assertThat(stores.router.take(Duration.ofMillis(1))).isEmpty();
        }
    }

    private static void awaitWaiting(AtomicReference<Thread> waiter) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while ((waiter.get() == null || waiter.get().getState() != Thread.State.TIMED_WAITING) &&
                System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(waiter.get()).isNotNull();
        assertThat(waiter.get().getState()).isEqualTo(Thread.State.TIMED_WAITING);
    }

    private static SourceId source() {
        return new SourceId(UUID.randomUUID());
    }

    private static TestMessage message(String body, int priority) {
        return new TestMessage(body, priority, NOW);
    }

    private static void fails(ThrowingCallable operation, OutboundStage.Role role, OutboundStorageException.Reason reason) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(OutboundStorageException.class, failure -> {
            assertThat(failure.stage()).isEqualTo(role);
            assertThat(failure.reason()).isEqualTo(reason);
        });
    }

    private static final class Stores implements AutoCloseable {
        private final MemoryPendingMessageStore<TestMessage> pending;
        private final MemorySelectedRouterStore<TestMessage> router;

        private Stores(int pendingCapacity, int routerCapacity) {
            this(pendingCapacity, routerCapacity, TestMessage::copy);
        }

        private Stores(int pendingCapacity, int routerCapacity, UnaryOperator<TestMessage> snapshots) {
            pending = new MemoryPendingMessageStore<>(pendingCapacity, snapshots, msg -> msg.available);
            router = new MemorySelectedRouterStore<>(pending, routerCapacity, CLOCK);
            pending.open();
            router.open();
        }

        @Override
        public void close() {
            router.close();
            pending.close();
        }
    }

    private static final class TestMessage extends StandardMessage {
        private Instant available;
        private final List<String> tags = new ArrayList<>();

        private TestMessage(String body, int priority, Instant available) {
            this.body = body;
            this.priority = priority;
            this.available = available;
        }

        private TestMessage copy() {
            var copy = new TestMessage(body, priority, available);
            copy.tags.addAll(tags);
            return copy;
        }
    }
}
