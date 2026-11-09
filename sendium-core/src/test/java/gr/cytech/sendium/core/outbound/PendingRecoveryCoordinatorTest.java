package gr.cytech.sendium.core.outbound;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.file.FilePendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemoryPendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemoryRoutedWorkStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.INVALID_TRANSITION;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNSUPPORTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(20)
class PendingRecoveryCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    @TempDir
    Path root;

    @Test
    void readinessCoversLifecycleAndDoesNotEnumerateSources() {
        try (var harness = new Harness()) {
            assertThat(harness.coordinator.isReady()).isFalse();
            harness.coordinator.start();
            clearInvocations(harness.pending);
            assertThat(harness.coordinator.isReady()).isTrue();
            verify(harness.pending, never()).sourcesAfter(any(), anyInt());
            harness.coordinator.beginShutdown();
            assertThat(harness.coordinator.isReady()).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "router", "routed"})
    void anyFailedStorageStageMakesTheCoordinatorNotReady(String role) {
        try (var harness = new Harness()) {
            harness.coordinator.start();
            var stage = switch (role) {
                case "pending" -> harness.pending;
                case "router" -> harness.router;
                default -> harness.routed;
            };
            doReturn(new OutboundStage.Status("test", false, OutboundStage.State.FAILED)).when(stage).status();
            assertThat(harness.coordinator.isReady()).isFalse();
        }
    }

    @Test
    void ownsOneRecoveryAndRestoresOrdinaryAndMultipartSourcesBeforeAdmission() throws Exception {
        var ordinary = message("ordinary", null);
        var first = message("A", "0500037F0201");
        var duplicate = message("duplicate", "0500037F0201");
        var second = message("B", "0500037F0202");
        try (var pending = new FilePendingMessageStore(root, 32, Clock.fixed(NOW, ZoneOffset.UTC))) {
            pending.open();
            pending.admit(source(ordinary), ordinary);
            for (var part : new StandardMessage[]{first, duplicate, second}) {
                pending.admitPart(source(part), part, Duration.ofSeconds(60));
            }
        }
        try (var harness = new Harness()) {
            assertThat(harness.schedulerCreations).hasValue(0);
            assertThat(harness.coordinator.pendingMultipartRecovery()).isEmpty();
            assertThatThrownBy(() -> harness.coordinator.accept(source(ordinary), ordinary)).isInstanceOf(OutboundStorageException.class);
            harness.coordinator.start();
            var recovery = harness.coordinator.pendingMultipartRecovery().orElseThrow();
            assertThat(recovery.isReady()).isTrue();
            harness.coordinator.start();
            assertThat(harness.coordinator.pendingMultipartRecovery()).containsSame(recovery);
            assertThat(harness.schedulerCreations).hasValue(1);
            verify(harness.scheduler, times(1)).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
            assertThat(harness.coordinator.selectToRouter(10)).isEqualTo(2);
            var selected = new ArrayList<OutboundWork.Selected<StandardMessage>>();
            selected.add(harness.coordinator.takeForRouting(Duration.ZERO).orElseThrow());
            selected.add(harness.coordinator.takeForRouting(Duration.ZERO).orElseThrow());
            assertThat(selected).extracting(work -> work.message().body).containsExactlyInAnyOrder("ordinary", "AB");
            assertThat(selected).anySatisfy(work -> assertThat(work.sources())
                    .containsExactlyInAnyOrder(source(first), source(duplicate), source(second)));
            assertThat(harness.pending.find(source(first)).orElseThrow().body).isEqualTo("A");
        }
    }

    @Test
    void unfinishedMemoryRoutedWorkReselectsAndReroutesOriginalPendingContentAfterRestart() throws Exception {
        var message = message("accepted", null);
        OutboundWork.SelectionId previous;
        try (var before = new Harness()) {
            before.coordinator.start();
            before.coordinator.accept(source(message), message);
            before.coordinator.selectToRouter(1);
            var selected = before.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            previous = selected.id();
            selected.message().body = "execution mutation";
            before.coordinator.recordToRouted(selected, new Destination<>("provider", selected.message()));
            assertThat(before.coordinator.takeFromRouted("provider", Duration.ZERO)).isPresent();
        }
        try (var after = new Harness()) {
            after.coordinator.start();
            assertThat(after.coordinator.takeFromRouted("provider", Duration.ZERO)).isEmpty();
            assertThat(after.coordinator.selectToRouter(1)).isEqualTo(1);
            var recovered = after.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(recovered.id()).isNotEqualTo(previous);
            assertThat(recovered.sources()).containsExactly(source(message));
            assertThat(recovered.message().body).isEqualTo("accepted");
        }
    }

    @Test
    void sharedRuntimeRecoveryRetainsEveryPartUntilRequiredHandoffAndWorksDuringDrain() throws Exception {
        try (var harness = new Harness()) {
            harness.coordinator.start();
            var recovery = harness.coordinator.pendingMultipartRecovery().orElseThrow();
            var first = message("A", "0500037F0201");
            var duplicate = message("duplicate", "0500037F0201");
            var second = message("B", "0500037F0202");
            for (var part : new StandardMessage[]{first, duplicate, second}) {
                harness.coordinator.acceptPart(source(part), part, Duration.ofSeconds(60));
                recovery.accepted(source(part));
            }
            assertThat(harness.coordinator.selectToRouter(1)).isEqualTo(1);
            var selected = harness.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            harness.coordinator.recordToRouted(selected, new Destination<>("provider", selected.message()));
            var routed = harness.coordinator.takeFromRouted("provider", Duration.ZERO).orElseThrow();
            var handoff = new CompletableFuture<Void>();
            var completed = harness.coordinator.complete(routed.id(), handoff).toCompletableFuture();
            assertThat(completed).isNotDone();
            assertThat(harness.pending.sourcesAfter(null, 10)).hasSize(3);
            harness.coordinator.beginShutdown();
            assertThat(recovery.isReady()).isFalse();
            var newMessage = message("new", null);
            assertThatThrownBy(() -> harness.coordinator.accept(source(newMessage), newMessage))
                    .hasMessageContaining("reason:notAcceptingNewWork");
            handoff.complete(null);
            completed.join();
            assertThat(harness.pending.sourcesAfter(null, 10)).isEmpty();
            verify(harness.scheduler, atLeastOnce()).shutdown();
        }
    }

    @Test
    void unsafeRecoveryBlocksNewAdmissionAndSelectionEvenWhenThePendingBackendIsStillReady() {
        try (var harness = new Harness()) {
            harness.coordinator.start();
            var part = message("A", "0500037F0301");
            harness.coordinator.acceptPart(source(part), part, Duration.ofSeconds(60));
            doThrow(new OutboundStorageException(OutboundStage.Role.PENDING, INVALID_TRANSITION,
                    "testPublication|error|reason:invalidBinding"))
                    .when(harness.pending).makeHeldReady(any(), any());
            harness.clock.now = NOW.plusSeconds(60);
            harness.poll.get().run();
            assertThat(harness.pending.status().state()).isEqualTo(OutboundStage.State.READY);
            assertThat(harness.coordinator.pendingMultipartRecovery().orElseThrow().isReady()).isFalse();
            assertThat(harness.coordinator.isReady()).isFalse();
            var message = message("new", null);
            assertThatThrownBy(() -> harness.coordinator.accept(source(message), message)).hasMessageContaining("reason:pendingRecoveryNotReady");
            assertThatThrownBy(() -> harness.coordinator.acceptPart(source(part), part, Duration.ofSeconds(60)))
                    .hasMessageContaining("reason:pendingRecoveryNotReady");
            assertThatThrownBy(() -> harness.coordinator.selectToRouter(1)).hasMessageContaining("reason:pendingRecoveryNotReady");
            assertThat(harness.pending.find(source(part))).isPresent();
        }
    }

    @Test
    void failedRecoveryStartupClosesAllOwnedResourcesAndPreservesTheOriginalFailure() {
        var opening = new java.util.concurrent.RejectedExecutionException("testScheduler|error|reason:rejected");
        var cleanup = new IllegalStateException("testCleanup|error|reason:routerCloseFailed");
        try (var harness = new Harness()) {
            when(harness.scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                    .thenThrow(opening);
            doAnswer(invocation -> {
                invocation.callRealMethod();
                throw cleanup;
            }).when(harness.router).close();
            assertThatThrownBy(harness.coordinator::start).isSameAs(opening);
            assertThat(opening.getSuppressed()).containsExactly(cleanup);
            verify(harness.scheduler).shutdown();
            assertThat(harness.pending.status().state()).isEqualTo(OutboundStage.State.CLOSED);
            assertThat(harness.routed.status().state()).isEqualTo(OutboundStage.State.CLOSED);
            assertThatThrownBy(harness.coordinator::start).isInstanceOf(OutboundStorageException.class);
        }
        try (var reopened = new FilePendingMessageStore(root, 32)) {
            reopened.open(); // Startup rollback must release the exclusive spool lock.
        }
    }

    @Test
    void corruptSourceFailsBeforeCreatingRecoveryOrOpeningLaterStages() throws Exception {
        var message = message("accepted", null);
        try (var pending = new FilePendingMessageStore(root, 32)) {
            pending.open();
            pending.admit(source(message), message);
        }
        String id = message.serial;
        Files.write(root.resolve("sources").resolve(id.substring(0, 2)).resolve(id + ".cbor"), new byte[0]);
        try (var harness = new Harness()) {
            assertThatThrownBy(harness.coordinator::start).isInstanceOf(OutboundStorageException.class);
            assertThat(harness.schedulerCreations).hasValue(0);
            verify(harness.router, never()).open();
            verify(harness.routed, never()).open();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void durableLaterStagesRemainUnsupported(boolean routed) {
        try (var harness = new Harness()) {
            var stage = routed ? harness.routed : harness.router;
            doReturn(new OutboundStage.Status("file", true, OutboundStage.State.NEW)).when(stage).status();
            assertThatThrownBy(harness.coordinator::start).isInstanceOfSatisfying(OutboundStorageException.class,
                    failure -> assertThat(failure.reason()).isEqualTo(UNSUPPORTED));
            assertThat(harness.schedulerCreations).hasValue(0);
            verify(stage, never()).open();
        }
    }

    @Test
    void memoryOnlyCompositionDoesNotCreateARecoveryThread() {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var pending = new MemoryPendingMessageStore<StandardMessage>(2, PendingRecoveryCoordinatorTest::copy);
        var router = new MemorySelectedRouterStore<>(pending, 1);
        var routed = new MemoryRoutedWorkStore<StandardMessage>(2, PendingRecoveryCoordinatorTest::copy);
        var creations = new AtomicInteger();
        try (var coordinator = new DefaultOutboundCoordinator<>(pending, router, routed, 2, clock, () -> {
            creations.incrementAndGet();
            throw new AssertionError("Memory composition must not create a recovery scheduler");
        })) {
            coordinator.start();
            assertThat(coordinator.pendingMultipartRecovery()).isEmpty();
            assertThat(creations).hasValue(0);
        }
    }

    @Test
    void durablePendingWithoutIndexedRecoveryIsNotSilentlyAdopted() {
        var pending = spy(new MemoryPendingMessageStore<StandardMessage>(2, PendingRecoveryCoordinatorTest::copy));
        var router = new MemorySelectedRouterStore<>(pending, 1);
        var routed = new MemoryRoutedWorkStore<StandardMessage>(2, PendingRecoveryCoordinatorTest::copy);
        doReturn(new OutboundStage.Status("external", true, OutboundStage.State.NEW)).when(pending).status();
        try (var coordinator = new DefaultOutboundCoordinator<>(pending, router, routed)) {
            assertThatThrownBy(coordinator::start).isInstanceOfSatisfying(OutboundStorageException.class,
                    failure -> assertThat(failure.reason()).isEqualTo(UNSUPPORTED));
            verify(pending, never()).open();
        }
    }

    @Test
    void configuredRecoveryBatchMustBePositiveBeforeAnyStageIsOpened() {
        try (var harness = new Harness()) {
            assertThatThrownBy(() -> new DefaultOutboundCoordinator<>(harness.pending, harness.router, harness.routed, 0))
                    .hasMessageContaining("reason:nonPositiveRecoveryBatchSize");
            verify(harness.pending, never()).open();
            assertThat(harness.schedulerCreations).hasValue(0);
        }
    }

    @Test
    void productionRecoveryThreadIsDaemonAndStopsBeforeTheSpoolCanBeReopened() throws Exception {
        var pending = new FilePendingMessageStore(root, 2);
        var router = new MemorySelectedRouterStore<>(pending, 1);
        var routed = new MemoryRoutedWorkStore<StandardMessage>(2, PendingRecoveryCoordinatorTest::copy);
        Thread worker;
        try (var coordinator = new DefaultOutboundCoordinator<>(pending, router, routed)) {
            coordinator.start();
            var threads = Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> thread.getName().equals("outbound-pending-recovery")).toList();
            assertThat(threads).hasSize(1);
            worker = threads.getFirst();
            assertThat(worker.isDaemon()).isTrue();
            assertThat(coordinator.pendingMultipartRecovery().orElseThrow().isReady()).isTrue();
        }
        worker.join(5000);
        assertThat(worker.isAlive()).isFalse();
        try (var reopened = new FilePendingMessageStore(root, 2)) {
            reopened.open();
        }
    }

    @Test
    void shutdownDoesNotHoldTheCoordinatorLockWhileWaitingForAPublishingRecoveryCallback() throws Exception {
        var harness = new Harness();
        var executor = Executors.newThreadPerTaskExecutor(Thread.ofPlatform().daemon(true).factory());
        var callbackEntered = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        boolean drained = false;
        try {
            harness.coordinator.start();
            var part = message("A", "0500037F0301");
            harness.coordinator.acceptPart(source(part), part, Duration.ofSeconds(60));
            doAnswer(invocation -> {
                callbackEntered.countDown();
                if (!releaseCallback.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("testCallback|error|reason:timeout");
                }
                return invocation.callRealMethod();
            }).when(harness.coordinator).makeHeldReady(any(), any());
            harness.clock.now = NOW.plusSeconds(60);
            var polling = executor.submit(() -> harness.poll.get().run());
            assertThat(callbackEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var stopping = executor.submit(harness.coordinator::beginShutdown);
            var shutdownVisible = executor.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        harness.coordinator.start();
                        Thread.yield();
                    } catch (OutboundStorageException failure) {
                        return failure.reason() == INVALID_TRANSITION;
                    }
                }
                return false;
            });
            assertThat(shutdownVisible.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(stopping.isDone()).isFalse();
            releaseCallback.countDown();
            polling.get(5, TimeUnit.SECONDS);
            stopping.get(5, TimeUnit.SECONDS);
            drained = true;
            assertThat(harness.pending.find(source(part))).isPresent();
        } finally {
            releaseCallback.countDown();
            executor.shutdownNow();
            if (drained) {
                harness.close();
            }
            // Daemon test threads allow a lock-inversion regression to fail without hanging the test JVM.
        }
    }

    private final class Harness implements AutoCloseable {
        private final MutableClock clock = new MutableClock();
        private final FilePendingMessageStore pending = spy(new FilePendingMessageStore(root, 32, clock));
        private final MemorySelectedRouterStore<StandardMessage> router = spy(new MemorySelectedRouterStore<>(pending, 8));
        private final MemoryRoutedWorkStore<StandardMessage> routed = spy(new MemoryRoutedWorkStore<>(32, PendingRecoveryCoordinatorTest::copy));
        private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        private final ScheduledFuture<?> future = mock(ScheduledFuture.class);
        private final AtomicReference<Runnable> poll = new AtomicReference<>();
        private final AtomicInteger schedulerCreations = new AtomicInteger();
        private final DefaultOutboundCoordinator<StandardMessage> coordinator;

        private Harness() {
            when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
                poll.set(invocation.getArgument(0));
                return future;
            });
            coordinator = spy(new DefaultOutboundCoordinator<>(pending, router, routed, 2, clock, () -> {
                schedulerCreations.incrementAndGet();
                return scheduler;
            }));
        }

        @Override
        public void close() {
            coordinator.close();
        }
    }

    private static StandardMessage copy(StandardMessage message) {
        return PendingMessageData.capture(message).restore();
    }

    private static SourceId source(StandardMessage message) {
        return new SourceId(UUID.fromString(message.serial));
    }

    private static StandardMessage message(String body, String header) {
        var message = new StandardMessage();
        message.serial = UUID.randomUUID().toString();
        message.body = body;
        message.owner_id = "account";
        message.systemId = "system";
        message.from = "sender";
        message.to = "recipient";
        message.binheader = header;
        return message;
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
