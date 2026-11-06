package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.OWNERSHIP_CONFLICT;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilePendingRouterTest {
    @TempDir
    Path root;

    @Test
    void boundedSelectionRetainsPendingAndExcludesAlreadySelectedSources() throws Exception {
        try (var pending = pending(); var router = new MemorySelectedRouterStore<>(pending, 1)) {
            pending.open();
            router.open();
            pending.admit(source(1), message(source(1), "first", 1));
            pending.admit(source(2), message(source(2), "second", 0));
            assertThat(router.selectToRouter(10)).isEqualTo(1);
            Selected<StandardMessage> selected = router.take(Duration.ZERO).orElseThrow();
            assertThat(selected.sources()).containsExactly(source(1));
            assertThat(pending.find(source(1))).isPresent();
            assertThat(router.selectToRouter(10)).isZero();
            router.markRouted(selected.id());
            pending.admit(source(1), message(source(1), "replacement", 99));
            assertThat(router.selectToRouter(10)).isEqualTo(1);
            Selected<StandardMessage> second = router.take(Duration.ZERO).orElseThrow();
            assertThat(second.sources()).containsExactly(source(2));
            router.markRouted(second.id());
            assertThat(router.selectToRouter(10)).isZero();
            assertThat(pending.sourcesAfter(null, 10)).hasSize(2);
            assertThatThrownBy(() -> router.complete(selected.id())).isInstanceOf(OutboundStorageException.class);
            pending.complete(selected.sources());
            router.complete(selected.id());
            router.complete(selected.id());
            assertThat(pending.find(source(1))).isEmpty();
            assertThat(router.status()).isEqualTo(new OutboundStage.Status("memory", false, OutboundStage.State.READY));
        }
    }

    @Test
    void releasePreservesExecutionMutationsButNotAcceptedPayloadMutations() throws Exception {
        try (var pending = pending(); var router = new MemorySelectedRouterStore<>(pending, 1)) {
            pending.open();
            router.open();
            pending.admit(source(1), message(source(1), "accepted", 0));
            router.selectToRouter(1);
            var selected = router.take(Duration.ZERO).orElseThrow();
            selected.message().body = "execution mutation";
            assertThatThrownBy(() -> router.release(new Selected<>(selected.id(), selected.sources(), selected.message())))
                    .isInstanceOf(OutboundStorageException.class);
            router.release(selected);
            router.release(selected);
            selected.message().body = "mutation after release";
            var again = router.take(Duration.ZERO).orElseThrow();
            assertThat(again.id()).isEqualTo(selected.id());
            assertThat(again.message().body).isEqualTo("execution mutation");
            assertThat(pending.find(source(1)).orElseThrow().body).isEqualTo("accepted");
            assertThat(router.take(Duration.ZERO)).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"queued", "taken", "routed"})
    void restartReselectsOriginalAcceptedSourceRegardlessOfVolatilePhase(String phase) throws Exception {
        SelectionId previous;
        try (var pending = pending(); var router = new MemorySelectedRouterStore<>(pending, 1)) {
            pending.open();
            router.open();
            pending.admit(source(1), message(source(1), "accepted", 0));
            router.selectToRouter(1);
            var selected = router.take(Duration.ZERO).orElseThrow();
            previous = selected.id();
            selected.message().body = "runtime only";
            if (phase.equals("queued")) {
                router.release(selected);
            } else if (phase.equals("routed")) {
                router.markRouted(selected.id());
            }
        }
        try (var pending = pending(); var router = new MemorySelectedRouterStore<>(pending, 1)) {
            pending.open();
            router.open();
            assertThat(router.selectToRouter(1)).isEqualTo(1);
            var recovered = router.take(Duration.ZERO).orElseThrow();
            assertThat(recovered.sources()).containsExactly(source(1));
            assertThat(recovered.id()).isNotEqualTo(previous);
            assertThat(recovered.message().body).isEqualTo("accepted");
        }
    }

    @Test
    void exclusiveRouterOwnershipAndHeldExclusionArePreserved() {
        try (var pending = pending(); var router = new MemorySelectedRouterStore<>(pending, 1);
                var other = new MemorySelectedRouterStore<>(pending, 1)) {
            pending.open();
            router.open();
            assertThatThrownBy(other::open).isInstanceOfSatisfying(OutboundStorageException.class,
                    failure -> assertThat(failure.reason()).isEqualTo(OWNERSHIP_CONFLICT));
            pending.admitHeld(source(1), message(source(1), "held", 100));
            assertThat(router.selectToRouter(1)).isZero();
            router.close();
            assertThat(pending.find(source(1))).isPresent();
        }
    }

    @Test
    void failedHandoffLeavesSourceEligibleAndAcceptedContentUntouched() {
        try (var pending = pending()) {
            pending.open();
            pending.admit(source(1), message(source(1), "accepted", 0));
            pending.selectionLock().lock();
            try {
                assertThatThrownBy(() -> pending.stageNext(Instant.now(), selected -> {
                    selected.message().body = "not accepted";
                    throw new IllegalStateException("test interruption");
                })).isInstanceOf(IllegalStateException.class);
                var captured = new ArrayList<Selected<StandardMessage>>();
                assertThat(pending.stageNext(Instant.now(), captured::add)).isTrue();
                assertThat(captured.getFirst().message().body).isEqualTo("accepted");
                assertThat(pending.stageNext(Instant.now(), captured::add)).isFalse();
            } finally {
                pending.selectionLock().unlock();
            }
        }
    }

    @Test
    void unsafeIndexRemovalAfterPublicationFailsClosedAndRecoversSource() throws Exception {
        try (var pending = pending()) {
            pending.open();
            pending.admit(source(1), message(source(1), "accepted", 0));
            pending.selectionLock().lock();
            try {
                var captured = new ArrayList<Selected<StandardMessage>>();
                assertThatThrownBy(() -> pending.stageNext(Instant.now(), selected -> {
                    captured.add(selected);
                    try {
                        FolderPendingIndex.discard(root.resolve("derived/ready/0"));
                        Files.writeString(root.resolve("derived/ready/0"), "broken reference tree");
                    } catch (java.io.IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                })).isInstanceOf(OutboundStorageException.class);
                assertThat(captured).hasSize(1);
                assertThat(pending.status().state()).isEqualTo(OutboundStage.State.FAILED);
            } finally {
                pending.selectionLock().unlock();
            }
        }
        try (var pending = pending(); var router = new MemorySelectedRouterStore<>(pending, 1)) {
            pending.open();
            router.open();
            assertThat(router.selectToRouter(1)).isEqualTo(1);
            assertThat(router.take(Duration.ZERO).orElseThrow().sources()).containsExactly(source(1));
        }
    }

    @Test
    void closingPendingWakesWaitingRouterAndReportsUnavailable() throws Exception {
        try (var pending = pending(); var router = new MemorySelectedRouterStore<>(pending, 1);
                var executor = Executors.newSingleThreadExecutor()) {
            pending.open();
            router.open();
            var started = new CountDownLatch(1);
            var result = executor.submit(() -> {
                started.countDown();
                try {
                    router.take(Duration.ofHours(1));
                    return null;
                } catch (OutboundStorageException failure) {
                    return failure.reason();
                }
            });
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                pending.close();
                assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(UNAVAILABLE);
            } finally {
                result.cancel(true);
            }
            assertThat(router.status().state()).isEqualTo(OutboundStage.State.FAILED);
        }
    }

    private FilePendingMessageStore pending() {
        return new FilePendingMessageStore(root, 10);
    }

    private static SourceId source(long id) {
        return new SourceId(new UUID(0, id));
    }

    private static StandardMessage message(SourceId source, String body, int priority) {
        var message = new StandardMessage();
        message.serial = source.value().toString();
        message.body = body;
        message.priority = priority;
        return message;
    }
}
