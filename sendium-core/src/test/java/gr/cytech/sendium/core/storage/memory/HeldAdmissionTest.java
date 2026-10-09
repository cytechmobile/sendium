package gr.cytech.sendium.core.storage.memory;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.DefaultOutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.CAPACITY_EXCEEDED;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.INVALID_TRANSITION;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNSUPPORTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(10)
class HeldAdmissionTest {
    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void heldSourcesAreRetainedButNeverIndividuallySelected() throws Exception {
        try (var stores = new Stores(3, 3)) {
            SourceId first = source();
            SourceId second = source();
            Payload part = payload("first", 3);
            part.tags.add("original");
            stores.pending.admitHeld(first, part);
            stores.pending.admitHeld(second, payload("second", 3));
            part.body = "changed";
            part.tags.clear();
            assertThat(stores.pending.find(first).orElseThrow().body).isEqualTo("first");
            assertThat(stores.pending.find(first).orElseThrow().tags).containsExactly("original");
            assertThat(stores.router.selectToRouter(3)).isZero();
            assertThat(stores.router.take(Duration.ZERO)).isEmpty();
            SourceId ordinary = source();
            stores.pending.admit(ordinary, payload("ordinary", 1));
            assertThat(stores.router.selectToRouter(3)).isEqualTo(1);
            assertThat(stores.router.take(Duration.ZERO).orElseThrow().sources()).containsExactly(ordinary);
        }
    }

    @Test
    void publicationAtFullCapacitySelectsOneAggregateWithAllOriginalSources() throws Exception {
        try (var stores = new Stores(2, 1)) {
            SourceId first = source();
            SourceId second = source();
            Set<SourceId> sources = new HashSet<>(Set.of(first, second));
            stores.pending.admitHeld(first, payload("first", 2));
            stores.pending.admitHeld(second, payload("second", 2));
            fails(() -> stores.pending.admitHeld(source(), payload("full", 2)), CAPACITY_EXCEEDED);
            Payload aggregate = payload("firstsecond", 2);
            aggregate.tags.add("assembled");
            stores.pending.makeHeldReady(sources, aggregate);
            sources.clear();
            aggregate.body = "changed";
            aggregate.tags.clear();
            assertThat(stores.router.selectToRouter(10)).isEqualTo(1);
            var selected = stores.router.take(Duration.ZERO).orElseThrow();
            assertThat(selected.sources()).containsExactlyInAnyOrder(first, second);
            assertThat(selected.message().body).isEqualTo("firstsecond");
            assertThat(selected.message().tags).containsExactly("assembled");
            assertThat(stores.pending.find(first).orElseThrow().body).isEqualTo("first");
            assertThat(stores.pending.find(second).orElseThrow().body).isEqualTo("second");
            assertThat(stores.router.selectToRouter(10)).isZero();
            assertThat(stores.router.take(Duration.ZERO)).isEmpty();
            stores.router.markRouted(selected.id());
            fails(() -> stores.pending.admit(source(), payload("still full", 2)), CAPACITY_EXCEEDED);
            stores.pending.complete(selected.sources());
            stores.router.complete(selected.id());
            stores.pending.admit(source(), payload("next", 2));
        }
    }

    @Test
    void publicationIsFirstWinsBeforeAndAfterSelectionAndRouting() throws Exception {
        AtomicInteger snapshots = new AtomicInteger();
        try (var stores = new Stores(2, 1, message -> {
            snapshots.incrementAndGet();
            return message.copy();
        })) {
            var sources = Set.of(source(), source());
            sources.forEach(source -> stores.pending.admitHeld(source, payload("part", 2)));
            stores.pending.makeHeldReady(sources, payload("first publication", 2));
            int count = snapshots.get();
            stores.pending.makeHeldReady(new HashSet<>(sources), payload("replacement", 3));
            assertThat(snapshots).hasValue(count);
            stores.router.selectToRouter(1);
            stores.pending.makeHeldReady(sources, payload("after selection", 3));
            var selected = stores.router.take(Duration.ZERO).orElseThrow();
            assertThat(selected.message().body).isEqualTo("first publication");
            stores.pending.makeHeldReady(sources, payload("while taken", 3));
            stores.router.markRouted(selected.id());
            stores.pending.makeHeldReady(sources, payload("after routing", 3));
            assertThat(stores.router.selectToRouter(1)).isZero();
            assertThat(stores.router.take(Duration.ZERO)).isEmpty();
            stores.pending.complete(sources);
            stores.router.complete(selected.id());
            fails(() -> stores.pending.makeHeldReady(sources, payload("after cleanup", 2)), INVALID_TRANSITION);
            assertThat(stores.router.selectToRouter(1)).isZero();
        }
    }

    @Test
    void duplicateAdmissionCannotPromoteHeldWorkOrHoldOrdinaryWork() throws Exception {
        try (var stores = new Stores(2, 2)) {
            SourceId held = source();
            SourceId ordinary = source();
            stores.pending.admitHeld(held, payload("held", 2));
            stores.pending.admit(ordinary, payload("ordinary", 1));
            stores.pending.admit(held, payload("promote", 3));
            stores.pending.admitHeld(held, payload("overwrite", 3));
            stores.pending.admitHeld(ordinary, payload("hold", 3));
            assertThat(stores.router.selectToRouter(2)).isEqualTo(1);
            assertThat(stores.router.take(Duration.ZERO).orElseThrow().sources()).containsExactly(ordinary);
            assertThat(stores.pending.find(held).orElseThrow().body).isEqualTo("held");
            fails(() -> stores.pending.makeHeldReady(Set.of(ordinary), payload("ordinary replacement", 2)), INVALID_TRANSITION);
        }
    }

    @Test
    void callerCanPublishAnExpiredPartIndividuallyWithoutReleasingOtherHeldParts() throws Exception {
        try (var stores = new Stores(2, 2)) {
            SourceId first = source();
            SourceId second = source();
            stores.pending.admitHeld(first, payload("first", 2));
            stores.pending.admitHeld(second, payload("second", 2));
            stores.pending.makeHeldReady(Set.of(first), stores.pending.find(first).orElseThrow());
            assertThat(stores.router.selectToRouter(2)).isEqualTo(1);
            var selected = stores.router.take(Duration.ZERO).orElseThrow();
            assertThat(selected.sources()).containsExactly(first);
            assertThat(selected.message().body).isEqualTo("first");
            assertThat(stores.pending.find(second)).isPresent();
            assertThat(stores.router.selectToRouter(2)).isZero();
            fails(() -> stores.pending.makeHeldReady(Set.of(first, second), payload("late aggregate", 2)), INVALID_TRANSITION);
            stores.pending.makeHeldReady(Set.of(second), stores.pending.find(second).orElseThrow());
            assertThat(stores.router.selectToRouter(2)).isEqualTo(1);
        }
    }

    @Test
    void missingOrOverlappingSourceSetsFailWithoutBindingUnpublishedSources() throws Exception {
        try (var stores = new Stores(3, 3)) {
            SourceId first = source();
            SourceId second = source();
            SourceId third = source();
            for (var source : Set.of(first, second, third)) {
                stores.pending.admitHeld(source, payload("part", 2));
            }
            fails(() -> stores.pending.makeHeldReady(Set.of(first, source()), payload("unknown", 2)), INVALID_TRANSITION);
            assertThat(stores.router.selectToRouter(3)).isZero();
            stores.pending.makeHeldReady(Set.of(first, second), payload("aggregate", 2));
            fails(() -> stores.pending.makeHeldReady(Set.of(first), payload("subset", 2)), INVALID_TRANSITION);
            fails(() -> stores.pending.makeHeldReady(Set.of(first, second, third), payload("superset", 2)), INVALID_TRANSITION);
            fails(() -> stores.pending.makeHeldReady(Set.of(first, third), payload("overlap", 2)), INVALID_TRANSITION);
            stores.pending.makeHeldReady(Set.of(third), payload("third", 2));
            assertThat(stores.router.selectToRouter(3)).isEqualTo(2);
            assertThat(stores.router.take(Duration.ZERO).orElseThrow().sources()).containsExactlyInAnyOrder(first, second);
            assertThat(stores.router.take(Duration.ZERO).orElseThrow().sources()).containsExactly(third);
        }
    }

    @Test
    void snapshotFailureLeavesHeldSourcesAvailableForACompletePublicationRetry() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(false);
        try (var stores = new Stores(2, 2, message -> {
            if (fail.get()) {
                throw new IllegalStateException("snapshot unavailable");
            }
            return message.copy();
        })) {
            var sources = Set.of(source(), source());
            sources.forEach(source -> stores.pending.admitHeld(source, payload("part", 2)));
            fail.set(true);
            fails(() -> stores.pending.makeHeldReady(sources, payload("aggregate", 2)), UNAVAILABLE);
            fail.set(false);
            sources.forEach(source -> assertThat(stores.pending.find(source)).isPresent());
            assertThat(stores.router.selectToRouter(2)).isZero();
            stores.pending.makeHeldReady(sources, payload("aggregate", 2));
            assertThat(stores.router.selectToRouter(2)).isEqualTo(1);
            assertThat(stores.router.take(Duration.ZERO).orElseThrow().sources()).isEqualTo(sources);
        }
    }

    @Test
    void eligibilityIsEvaluatedAtPublicationAndFailureDoesNotPublishPartOfAGroup() throws Exception {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicBoolean fail = new AtomicBoolean(true);
        try (var pending = new MemoryPendingMessageStore<Payload>(2, Payload::copy, message -> {
            evaluations.incrementAndGet();
            if (fail.get()) {
                throw new IllegalStateException("eligibility unavailable");
            }
            return message.available;
        }); var router = new MemorySelectedRouterStore<>(pending, 1, CLOCK)) {
            pending.open();
            router.open();
            var sources = Set.of(source(), source());
            sources.forEach(source -> pending.admitHeld(source, new Payload("part", 3, null)));
            assertThat(evaluations).hasValue(0);
            fails(() -> pending.makeHeldReady(sources, payload("aggregate", 2)), UNSUPPORTED);
            assertThat(router.selectToRouter(1)).isZero();
            fail.set(false);
            pending.makeHeldReady(sources, payload("aggregate", 2));
            assertThat(router.selectToRouter(1)).isEqualTo(1);
            assertThat(router.take(Duration.ZERO).orElseThrow().message().body).isEqualTo("aggregate");
            assertThat(evaluations).hasValue(2);
        }
    }

    @Test
    void preparedPriorityAndEligibilityStillPassThroughBoundedSelection() throws Exception {
        try (var stores = new Stores(4, 1)) {
            SourceId ordinary = source();
            SourceId low = source();
            SourceId due = source();
            SourceId future = source();
            stores.pending.admit(ordinary, payload("ordinary", 2));
            for (var source : Set.of(low, due, future)) {
                stores.pending.admitHeld(source, payload("raw high priority", 5));
            }
            stores.pending.makeHeldReady(Set.of(low), payload("prepared low", 1));
            stores.pending.makeHeldReady(Set.of(due), payload("prepared high", 3));
            stores.pending.makeHeldReady(Set.of(future), new Payload("future", 9, NOW.plusSeconds(1)));
            assertThat(stores.router.take(Duration.ZERO)).isEmpty();
            assertThat(stores.router.selectToRouter(10)).isEqualTo(1);
            var selected = stores.router.take(Duration.ZERO).orElseThrow();
            assertThat(selected.message().body).isEqualTo("prepared high");
            assertThat(stores.router.selectToRouter(10)).isZero();
            stores.router.markRouted(selected.id());
            assertThat(stores.router.selectToRouter(10)).isEqualTo(1);
            selected = stores.router.take(Duration.ZERO).orElseThrow();
            assertThat(selected.message().body).isEqualTo("ordinary");
            stores.router.markRouted(selected.id());
            stores.router.selectToRouter(10);
            selected = stores.router.take(Duration.ZERO).orElseThrow();
            assertThat(selected.message().body).isEqualTo("prepared low");
            stores.router.markRouted(selected.id());
            assertThat(stores.router.selectToRouter(10)).isZero();
        }
    }

    @Test
    void concurrentPublicationAndSelectionYieldOneWholeGroup() throws Exception {
        try (var stores = new Stores(2, 2); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var sources = Set.of(source(), source());
            sources.forEach(source -> stores.pending.admitHeld(source, payload("part", 2)));
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 16; i++) {
                final int index = i;
                jobs.add(executor.submit(() -> {
                    stores.pending.makeHeldReady(sources, payload("aggregate-" + index, 2));
                    stores.router.selectToRouter(2);
                }));
            }
            for (var job : jobs) {
                job.get(2, TimeUnit.SECONDS);
            }
            var selected = stores.router.take(Duration.ZERO).orElseThrow();
            assertThat(selected.sources()).isEqualTo(sources);
            assertThat(selected.message().body).startsWith("aggregate-");
            assertThat(stores.router.take(Duration.ZERO)).isEmpty();
            assertThat(stores.router.selectToRouter(2)).isZero();
        }
    }

    @Test
    void competingGroupsCannotBothOwnAnOverlappingSource() throws Exception {
        try (var stores = new Stores(3, 3); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            SourceId shared = source();
            SourceId left = source();
            SourceId right = source();
            for (var source : Set.of(shared, left, right)) {
                stores.pending.admitHeld(source, payload("part", 2));
            }
            var groups = List.of(Set.of(shared, left), Set.of(shared, right));
            var jobs = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (var group : groups) {
                jobs.add(executor.submit(() -> {
                    try {
                        stores.pending.makeHeldReady(group, payload("aggregate", 2));
                        return true;
                    } catch (OutboundStorageException failure) {
                        assertThat(failure.reason()).isEqualTo(INVALID_TRANSITION);
                        return false;
                    }
                }));
            }
            boolean leftWon = jobs.getFirst().get(2, TimeUnit.SECONDS);
            assertThat(jobs.getLast().get(2, TimeUnit.SECONDS)).isNotEqualTo(leftWon);
            assertThat(stores.router.selectToRouter(3)).isEqualTo(1);
            var selected = stores.router.take(Duration.ZERO).orElseThrow();
            assertThat(selected.sources()).isEqualTo(groups.get(leftWon ? 0 : 1));
            SourceId unbound = leftWon ? right : left;
            stores.pending.makeHeldReady(Set.of(unbound), payload("unbound", 2));
            assertThat(stores.router.selectToRouter(3)).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void coordinatorRetainsHeldSourcesThroughHandoffOrUnroutedDiscard(boolean discard) throws Exception {
        try (var pending = new MemoryPendingMessageStore<Payload>(2, Payload::copy);
             var router = new MemorySelectedRouterStore<>(pending, 1);
             var routed = new MemoryRoutedWorkStore<Payload>(1, Payload::copy);
             var coordinator = new DefaultOutboundCoordinator<>(pending, router, routed)) {
            coordinator.start();
            var sources = Set.of(source(), source());
            sources.forEach(source -> coordinator.acceptHeld(source, payload("part", 2)));
            assertThat(coordinator.selectToRouter(1)).isZero();
            coordinator.makeHeldReady(sources, payload("aggregate", 2));
            assertThat(coordinator.takeForRouting(Duration.ZERO)).isEmpty();
            coordinator.selectToRouter(1);
            var selected = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(selected.sources()).isEqualTo(sources);
            var handoff = new CompletableFuture<Void>();
            java.util.concurrent.CompletionStage<Void> completion;
            if (discard) {
                completion = coordinator.discard(selected.id(), handoff);
            } else {
                coordinator.recordToRouted(selected, new Destination<>("A", selected.message()));
                var work = coordinator.takeFromRouted("A", Duration.ZERO).orElseThrow();
                completion = coordinator.complete(work.id(), handoff);
            }
            coordinator.makeHeldReady(sources, payload("late duplicate", 2));
            sources.forEach(source -> assertThat(pending.find(source).orElseThrow().body).isEqualTo("part"));
            assertThat(coordinator.selectToRouter(1)).isZero();
            handoff.complete(null);
            completion.toCompletableFuture().join();
            sources.forEach(source -> assertThat(pending.find(source)).isEmpty());
            fails(() -> coordinator.makeHeldReady(sources, payload("after cleanup", 2)), INVALID_TRANSITION);
        }
    }

    @Test
    void shutdownStartAllowsExistingHeldWorkToBecomeReadyButRejectsNewAdmissionAndSelection() {
        try (var pending = new MemoryPendingMessageStore<Payload>(1, Payload::copy);
             var router = new MemorySelectedRouterStore<>(pending, 1);
             var routed = new MemoryRoutedWorkStore<Payload>(1, Payload::copy);
             var coordinator = new DefaultOutboundCoordinator<>(pending, router, routed)) {
            SourceId source = source();
            fails(() -> coordinator.acceptHeld(source, payload("part", 2)), UNAVAILABLE);
            coordinator.start();
            coordinator.acceptHeld(source, payload("part", 2));
            coordinator.beginShutdown();
            coordinator.makeHeldReady(Set.of(source), payload("released at shutdown", 2));
            assertThat(pending.find(source)).isPresent();
            fails(() -> coordinator.acceptHeld(source(), payload("new", 2)), UNAVAILABLE);
            fails(() -> coordinator.selectToRouter(1), UNAVAILABLE);
        }
    }

    @Test
    void invalidPublicationCannotCreateSourcesAndNewInstancesRecoverNoHeldOrReadyState() {
        var sources = Set.of(source());
        try (var stores = new Stores(1, 1)) {
            assertThatThrownBy(() -> stores.pending.makeHeldReady(Set.of(), payload("empty", 2)))
                    .isInstanceOf(IllegalArgumentException.class);
            fails(() -> stores.pending.makeHeldReady(sources, payload("unknown", 2)), INVALID_TRANSITION);
            stores.pending.admitHeld(sources.iterator().next(), payload("held", 2));
            assertThatThrownBy(() -> stores.pending.makeHeldReady(sources, null)).isInstanceOf(NullPointerException.class);
        }
        try (var fresh = new Stores(1, 1)) {
            assertThat(fresh.pending.find(sources.iterator().next())).isEmpty();
            fails(() -> fresh.pending.makeHeldReady(sources, payload("unknown", 2)), INVALID_TRANSITION);
            fresh.pending.close();
            fails(() -> fresh.pending.admitHeld(source(), payload("closed", 2)), UNAVAILABLE);
            fails(() -> fresh.pending.makeHeldReady(sources, payload("closed", 2)), UNAVAILABLE);
        }
    }

    private static Payload payload(String body, int priority) {
        return new Payload(body, priority, NOW);
    }

    private static SourceId source() {
        return new SourceId(UUID.randomUUID());
    }

    private static void fails(ThrowingCallable operation, OutboundStorageException.Reason reason) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(OutboundStorageException.class,
                failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    private static final class Stores implements AutoCloseable {
        private final MemoryPendingMessageStore<Payload> pending;
        private final MemorySelectedRouterStore<Payload> router;

        private Stores(int pendingCapacity, int routerCapacity) {
            this(pendingCapacity, routerCapacity, Payload::copy);
        }

        private Stores(int pendingCapacity, int routerCapacity, UnaryOperator<Payload> snapshots) {
            pending = new MemoryPendingMessageStore<>(pendingCapacity, snapshots, message -> message.available);
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

    private static final class Payload extends StandardMessage {
        private final Instant available;
        private final List<String> tags = new ArrayList<>();

        private Payload(String body, int priority, Instant available) {
            this.body = body;
            this.priority = priority;
            this.available = available;
        }

        private Payload copy() {
            var copy = new Payload(body, priority, available);
            copy.tags.addAll(tags);
            return copy;
        }
    }
}
