package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import gr.cytech.sendium.core.storage.pending.PendingMultipartRecovery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.INVALID_TRANSITION;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileIndexedMultipartRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    @TempDir
    Path root;

    @Test
    void completeGroupRestoresInOrdinalOrderAndRetainsDuplicateOwnership() throws Exception {
        SourceId first;
        SourceId duplicate;
        SourceId second;
        try (var before = new Harness(NOW)) {
            first = before.part(1, 2, 1, "A", Duration.ofSeconds(60));
            duplicate = before.part(1, 2, 1, "duplicate", Duration.ofSeconds(60));
            second = before.part(1, 2, 2, "B", Duration.ofSeconds(60));
        }
        try (var after = new Harness(NOW.plusSeconds(20)); var router = new MemorySelectedRouterStore<>(after.pending, 2)) {
            after.recovery.reconstruct(1);
            router.open();
            assertThat(after.attempts).hasSize(1);
            assertThat(router.selectToRouter(2)).isEqualTo(1);
            var selected = router.take(Duration.ZERO).orElseThrow();
            assertThat(selected.sources()).containsExactlyInAnyOrder(first, duplicate, second);
            assertThat(selected.message().body).isEqualTo("AB");
            assertThat(selected.message().binheader).isNull();
            assertThat(after.pending.find(first).orElseThrow().body).isEqualTo("A");
            after.recovery.accepted(duplicate);
            after.tick();
            assertThat(after.attempts).hasSize(1);
        }
    }

    @Test
    void restartUsesOriginalDeadlineAndExpiresOnlyAtItsExactInstant() throws Exception {
        SourceId first;
        SourceId duplicate;
        try (var before = new Harness(NOW)) {
            first = before.part(2, 3, 1, "A", Duration.ofSeconds(60));
            duplicate = before.part(2, 3, 1, "duplicate", Duration.ofSeconds(60));
        }
        try (var after = new Harness(NOW.plusSeconds(50)); var router = new MemorySelectedRouterStore<>(after.pending, 2)) {
            after.recovery.reconstruct(2);
            router.open();
            after.clock.now = NOW.plusSeconds(60).minusNanos(1);
            after.tick();
            assertThat(after.attempts).isEmpty();
            after.clock.now = NOW.plusSeconds(60);
            after.tick();
            assertThat(after.attempts).containsExactly(Set.of(first));
            assertThat(router.selectToRouter(2)).isEqualTo(1);
            var selected = router.take(Duration.ZERO).orElseThrow();
            assertThat(selected.sources()).containsExactlyInAnyOrder(first, duplicate);
            assertThat(selected.message().binheader).isNotNull();
        }
    }

    @Test
    void boundsEachStartupAndPollBatchWithOneTimerAndNoBacklogTraversal() {
        try (var harness = new Harness(NOW)) {
            for (int i = 0; i < 7; i++) {
                harness.part(10 + i, 1, 1, "complete", Duration.ofSeconds(60));
            }
            harness.recovery.reconstruct(2);
            assertThat(harness.recovery.isReady()).isTrue();
            assertThat(harness.attempts).hasSize(2);
            harness.tick();
            assertThat(harness.attempts).hasSize(4);
            harness.tick();
            assertThat(harness.attempts).hasSize(6);
            harness.tick();
            assertThat(harness.attempts).hasSize(7);
            harness.tick();
            assertThat(harness.attempts).hasSize(7);
            verify(harness.scheduler, times(1)).scheduleWithFixedDelay(any(Runnable.class), eq(100L), eq(100L), eq(TimeUnit.MILLISECONDS));
            verify(harness.scheduler, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
            verify(harness.pending, never()).sourcesAfter(any(), anyInt());
            verify(harness.pending, never()).multipartSources(any());
        }
    }

    @Test
    void runtimeNotificationsAssembleWithoutCreatingPerGroupTimers() throws Exception {
        try (var harness = new Harness(NOW); var router = new MemorySelectedRouterStore<>(harness.pending, 1)) {
            harness.recovery.reconstruct(1);
            router.open();
            SourceId first = harness.part(20, 2, 1, "A", Duration.ofSeconds(60));
            harness.recovery.accepted(first);
            assertThat(harness.attempts).isEmpty();
            SourceId second = harness.part(20, 2, 2, "B", Duration.ofSeconds(60));
            harness.recovery.accepted(second);
            assertThat(router.selectToRouter(1)).isEqualTo(1);
            assertThat(router.take(Duration.ZERO).orElseThrow().message().body).isEqualTo("AB");
            verify(harness.scheduler, times(1)).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        }
    }

    @Test
    void manyUnexpiredGroupsNeedNoRuntimeGroupMapOrIndividualTimers() {
        try (var harness = new Harness(NOW)) {
            for (int i = 0; i < 32; i++) {
                harness.part(i, 3, 1, "incomplete", Duration.ofSeconds(60));
            }
            harness.recovery.reconstruct(1);
            harness.tick();
            assertThat(harness.attempts).isEmpty();
            verify(harness.pending, never()).multipartGroup(any());
            verify(harness.pending, never()).sourcesAfter(any(), anyInt());
            verify(harness.scheduler, times(1)).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
            verify(harness.scheduler, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        }
    }

    @Test
    void transientPublicationFailureRetriesAfterOneSecondWithoutDuplicateReadyWork() throws Exception {
        try (var harness = new Harness(NOW); var router = new MemorySelectedRouterStore<>(harness.pending, 3)) {
            SourceId first = harness.part(30, 3, 1, "A", Duration.ofSeconds(60));
            harness.part(30, 3, 1, "duplicate", Duration.ofSeconds(60));
            SourceId second = harness.part(30, 3, 2, "B", Duration.ofSeconds(60));
            var failOnce = new AtomicBoolean(true);
            harness.action = (sources, message) -> {
                if (sources.contains(second) && failOnce.getAndSet(false)) {
                    throw unavailable();
                }
                harness.pending.makeHeldReady(sources, message);
            };
            harness.recovery.reconstruct(1);
            router.open();
            harness.clock.now = NOW.plusSeconds(60);
            harness.tick();
            assertThat(harness.recovery.isReady()).isTrue();
            assertThat(harness.attempts).containsExactly(Set.of(first), Set.of(second));
            harness.clock.now = NOW.plusSeconds(61).minusNanos(1);
            harness.tick();
            assertThat(harness.attempts).hasSize(2);
            harness.clock.now = NOW.plusSeconds(61);
            harness.tick();
            assertThat(harness.attempts).hasSize(4);
            assertThat(router.selectToRouter(10)).isEqualTo(2);
            assertThat(router.take(Duration.ZERO)).isPresent();
            assertThat(router.take(Duration.ZERO)).isPresent();
            assertThat(router.take(Duration.ZERO)).isEmpty();
        }
    }

    @Test
    void completionRacingPartialExpiryDoesNotResendFinishedOrdinalsOrFailRecovery() {
        try (var harness = new Harness(NOW)) {
            SourceId first = harness.part(40, 3, 1, "A", Duration.ofSeconds(60));
            SourceId duplicate = harness.part(40, 3, 1, "duplicate", Duration.ofSeconds(60));
            SourceId second = harness.part(40, 3, 2, "B", Duration.ofSeconds(60));
            var failOnce = new AtomicBoolean(true);
            harness.action = (sources, message) -> {
                if (sources.contains(second) && failOnce.getAndSet(false)) {
                    throw unavailable();
                }
                harness.pending.makeHeldReady(sources, message);
                if (sources.contains(first)) {
                    harness.pending.complete(Set.of(first, duplicate));
                }
            };
            harness.recovery.reconstruct(1);
            harness.clock.now = NOW.plusSeconds(60);
            harness.tick();
            harness.tick();
            assertThat(harness.recovery.isReady()).isTrue();
            assertThat(harness.attempts).containsExactly(Set.of(first), Set.of(second), Set.of(second));
            assertThat(harness.pending.find(first)).isEmpty();
            assertThat(harness.pending.find(second)).isPresent();
        }
    }

    @Test
    void completedGroupMayDisappearBeforeItsSchedulingReferenceIsFinished() {
        try (var harness = new Harness(NOW)) {
            SourceId source = harness.part(50, 1, 1, "A", Duration.ofSeconds(60));
            harness.action = (sources, message) -> {
                harness.pending.makeHeldReady(sources, message);
                harness.pending.complete(sources);
            };
            harness.recovery.reconstruct(1);
            harness.tick();
            assertThat(harness.recovery.isReady()).isTrue();
            assertThat(harness.attempts).containsExactly(Set.of(source));
            assertThat(harness.pending.sourcesAfter(null, 1)).isEmpty();
        }
    }

    @Test
    void unsafePublicationFailureCancelsTheSinglePollerAndBlocksNotifications() {
        try (var harness = new Harness(NOW)) {
            SourceId source = harness.part(60, 3, 1, "A", Duration.ofSeconds(60));
            harness.recovery.reconstruct(1);
            harness.action = (sources, message) -> {
                throw new OutboundStorageException(OutboundStage.Role.PENDING, INVALID_TRANSITION, "testPublication|error|reason:invalidBinding");
            };
            harness.clock.now = NOW.plusSeconds(60);
            harness.tick();
            assertThat(harness.recovery.isReady()).isFalse();
            verify(harness.future).cancel(false);
            assertThatThrownBy(() -> harness.recovery.accepted(source)).hasMessageContaining("reason:notReady");
            assertThat(harness.pending.find(source)).isPresent();
        }
    }

    @Test
    void corruptDeadlineIndexFailsClosedAndRebuildsFromAcceptedFacts() throws Exception {
        SourceId source;
        try (var harness = new Harness(NOW)) {
            source = harness.part(70, 3, 1, "A", Duration.ofSeconds(60));
            harness.recovery.reconstruct(1);
            FolderPendingIndex.discard(root.resolve("derived/multipart/due"));
            Files.writeString(root.resolve("derived/multipart/due"), "broken index");
            harness.clock.now = NOW.plusSeconds(60);
            harness.tick();
            assertThat(harness.recovery.isReady()).isFalse();
            assertThat(harness.pending.status().state()).isEqualTo(OutboundStage.State.FAILED);
        }
        try (var recovered = new Harness(NOW.plusSeconds(60))) {
            recovered.recovery.reconstruct(1);
            assertThat(recovered.attempts).containsExactly(Set.of(source));
        }
    }

    @Test
    void invalidDeadlineNanosecondsFailClosedInsteadOfBeingNormalized() throws Exception {
        try (var harness = new Harness(NOW)) {
            SourceId source = harness.part(75, 3, 1, "A", Duration.ofSeconds(60));
            UUID group = harness.pending.findMultipart(source).orElseThrow().groupId();
            Path keyPath = root.resolve("derived/multipart/groups").resolve(group.toString()).resolve("due-key");
            String key = Files.readString(keyPath);
            Files.writeString(keyPath, key.substring(0, 16) + "ffffffff" + key.substring(24));
            assertThatThrownBy(() -> harness.pending.multipartGroup(group)).isInstanceOf(OutboundStorageException.class);
            assertThat(harness.pending.status().state()).isEqualTo(OutboundStage.State.FAILED);
        }
    }

    @Test
    void startupPublicationFailureDoesNotClaimReadinessOrStartAPoller() {
        try (var harness = new Harness(NOW)) {
            harness.part(80, 1, 1, "A", Duration.ofSeconds(60));
            harness.action = (sources, message) -> {
                throw unavailable();
            };
            assertThatThrownBy(() -> harness.recovery.reconstruct(1)).isInstanceOf(OutboundStorageException.class);
            assertThat(harness.recovery.isReady()).isFalse();
            verify(harness.scheduler, never()).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        }
    }

    @Test
    void deadlineOrderingPreservesNanosecondsAndPreEpochInstants() {
        Instant beforeEpoch = Instant.parse("1960-01-01T00:00:00Z");
        try (var harness = new Harness(beforeEpoch)) {
            SourceId source = harness.part(90, 3, 1, "A", Duration.ofNanos(1));
            harness.recovery.reconstruct(1);
            assertThat(harness.attempts).isEmpty();
            harness.clock.now = beforeEpoch.plusNanos(1);
            harness.tick();
            assertThat(harness.attempts).containsExactly(Set.of(source));
        }
    }

    @Test
    void closingRecoveryDoesNotCloseTheBackendOrApplicationScheduler() {
        try (var harness = new Harness(NOW)) {
            SourceId source = harness.part(100, 3, 1, "A", Duration.ofSeconds(60));
            harness.recovery.reconstruct(1);
            harness.recovery.close();
            harness.clock.now = NOW.plusSeconds(60);
            harness.tick();
            assertThat(harness.attempts).isEmpty();
            assertThat(harness.pending.find(source)).isPresent();
            assertThat(harness.recovery.isReady()).isFalse();
            verify(harness.scheduler, never()).shutdown();
            assertThatThrownBy(() -> harness.recovery.reconstruct(1)).hasMessageContaining("reason:invalidReconstructionState");
        }
    }

    private static OutboundStorageException unavailable() {
        return new OutboundStorageException(OutboundStage.Role.PENDING, UNAVAILABLE, "testPublication|error|reason:transientFailure");
    }

    private final class Harness implements AutoCloseable {
        private final MutableClock clock;
        private final FilePendingMessageStore pending;
        private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        private final ScheduledFuture<?> future = mock(ScheduledFuture.class);
        private final AtomicReference<Runnable> poll = new AtomicReference<>();
        private final List<Set<SourceId>> attempts = new ArrayList<>();
        private final PendingMultipartRecovery<StandardMessage> recovery;
        private BiConsumer<Set<SourceId>, StandardMessage> action;

        private Harness(Instant now) {
            clock = new MutableClock(now);
            pending = spy(new FilePendingMessageStore(root, 64, clock));
            pending.open();
            action = pending::makeHeldReady;
            when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                    .thenAnswer(invocation -> {
                        poll.set(invocation.getArgument(0));
                        return future;
                    });
            recovery = new PendingMultipartRecovery<>(pending, clock, scheduler, (sources, message) -> {
                attempts.add(Set.copyOf(sources));
                action.accept(sources, message);
            });
        }

        private SourceId part(int reference, int total, int ordinal, String body, Duration timeout) {
            var message = new StandardMessage();
            message.serial = UUID.randomUUID().toString();
            message.body = body;
            message.owner_id = "account";
            message.systemId = "system";
            message.from = "sender";
            message.to = "recipient";
            message.binheader = "050003%02X%02X%02X".formatted(reference, total, ordinal);
            SourceId source = new SourceId(UUID.fromString(message.serial));
            pending.admitPart(source, message, timeout);
            return source;
        }

        private void tick() {
            poll.get().run();
        }

        @Override
        public void close() {
            recovery.close();
            pending.close();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

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
