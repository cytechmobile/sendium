package gr.cytech.sendium.core.outbound;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.file.FilePendingMessageStore;
import gr.cytech.sendium.core.storage.file.FileSelectedRouterStore;
import gr.cytech.sendium.core.storage.memory.MemoryRoutedWorkStore;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class DurableSelectionRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    @TempDir Path root;

    @Test
    void restoresSelectionAndReroutesMemoryDestination() throws Exception {
        var message = message("accepted", null);
        SelectionId id;
        try (var before = coordinator(pending(), CLOCK)) {
            before.start();
            before.accept(source(message), message);
            before.selectToRouter(1);
            var selected = before.takeForRouting(Duration.ZERO).orElseThrow();
            id = selected.id();
            selected.message().body = "volatile routed mutation";
            before.recordToRouted(selected, new Destination<>("old-provider", selected.message()));
        }
        var pending = spy(pending());
        try (var after = coordinator(pending, CLOCK)) {
            after.start();
            verify(pending, never()).stageNext(any(), any());
            assertThat(after.takeFromRouted("old-provider", Duration.ZERO)).isEmpty();
            var restored = after.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(restored.id()).isEqualTo(id);
            assertThat(restored.message().body).isEqualTo("accepted");
            after.recordToRouted(restored, new Destination<>("new-provider", restored.message()));
            var work = after.takeFromRouted("new-provider", Duration.ZERO).orElseThrow();
            assertThat(work.selection()).isEqualTo(id);
            assertThat(after.selectToRouter(1)).isZero();
            after.complete(work.id(), CompletableFuture.completedFuture(null)).toCompletableFuture().join();
            assertThat(pending.sourcesAfter(null, 1)).isEmpty();
        }
    }

    @Test
    void reservesSelectedMultipartBeforeReconstruction() throws Exception {
        var first = message("A", "0500037F0201");
        var duplicate = message("duplicate", "0500037F0201");
        var second = message("B", "0500037F0202");
        SelectionId id;
        try (var pending = pending(); var selected = new FileSelectedRouterStore(pending, 2, CLOCK)) {
            pending.open();
            selected.open();
            for (var part : new StandardMessage[]{first, duplicate, second}) {
                pending.admitPart(source(part), part, Duration.ofSeconds(60));
            }
            pending.makeMultipartReady(Set.of(source(first), source(second)));
            selected.selectToRouter(1);
            id = selected.take(Duration.ZERO).orElseThrow().id();
        }
        try (var after = coordinator(pending(), CLOCK)) {
            after.start();
            assertThat(after.isReady()).isTrue();
            var recovery = after.pendingMultipartRecovery().orElseThrow();
            recovery.accepted(source(duplicate));
            assertThat(after.selectToRouter(10)).isZero();
            var restored = after.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(restored.id()).isEqualTo(id);
            assertThat(restored.sources()).containsExactlyInAnyOrder(source(first), source(duplicate), source(second));
            assertThat(restored.message().body).isEqualTo("AB");
            assertThat(after.takeForRouting(Duration.ZERO)).isEmpty();
            try (var recipes = Files.list(root.resolve("derived/publications/work"))) {
                assertThat(recipes).isEmpty();
            }
            after.discard(id, CompletableFuture.completedFuture(null)).toCompletableFuture().join();
        }
    }

    @Test
    void failedHandoffRetainsDurableSelection() throws Exception {
        var message = message("accepted", null);
        SelectionId id;
        try (var before = coordinator(pending(), CLOCK)) {
            before.start();
            before.accept(source(message), message);
            before.selectToRouter(1);
            var selected = before.takeForRouting(Duration.ZERO).orElseThrow();
            id = selected.id();
            before.recordToRouted(selected, new Destination<>("provider", selected.message()));
            var work = before.takeFromRouted("provider", Duration.ZERO).orElseThrow();
            var result = before.complete(work.id(), CompletableFuture.failedFuture(new IllegalStateException("test handoff failure")));
            assertThatThrownBy(() -> result.toCompletableFuture().join()).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(Files.exists(selectedPath(id))).isTrue();
        }
        var pending = pending();
        try (var after = coordinator(pending, CLOCK)) {
            after.start();
            assertThat(pending.find(source(message))).isPresent();
            var restored = after.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(restored.id()).isEqualTo(id);
            after.discard(id, CompletableFuture.completedFuture(null)).toCompletableFuture().join();
            assertThat(Files.exists(selectedPath(id))).isFalse();
            assertThat(pending.find(source(message))).isEmpty();
        }
    }

    @Test
    void expiredGroupReconstructsOnlyUnownedOrdinals() throws Exception {
        var first = message("A", "0500037F0301");
        var duplicate = message("duplicate", "0500037F0301");
        var second = message("B", "0500037F0302");
        try (var pending = pending(); var selected = new FileSelectedRouterStore(pending, 2, CLOCK)) {
            pending.open();
            selected.open();
            for (var part : new StandardMessage[]{first, duplicate, second}) {
                pending.admitPart(source(part), part, Duration.ofSeconds(60));
            }
            pending.makeMultipartReady(Set.of(source(first)));
            selected.selectToRouter(1);
        }
        Clock later = Clock.fixed(NOW.plusSeconds(61), ZoneOffset.UTC);
        var pending = new FilePendingMessageStore(root, 32, later);
        try (var after = coordinator(pending, later)) {
            after.start();
            assertThat(after.selectToRouter(2)).isEqualTo(1);
            var a = after.takeForRouting(Duration.ZERO).orElseThrow();
            var b = after.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(Set.of(a.message().body, b.message().body)).containsExactlyInAnyOrder("A", "B");
            assertThat(a.sources()).doesNotContainAnyElementsOf(b.sources());
            after.discard(a.id(), CompletableFuture.completedFuture(null)).toCompletableFuture().join();
            after.discard(b.id(), CompletableFuture.completedFuture(null)).toCompletableFuture().join();
            assertThat(pending.sourcesAfter(null, 10)).isEmpty();
        }
    }

    private DefaultOutboundCoordinator<StandardMessage> coordinator(FilePendingMessageStore pending, Clock clock) {
        return new DefaultOutboundCoordinator<>(pending, new FileSelectedRouterStore(pending, 2, clock),
                new MemoryRoutedWorkStore<>(32, value -> PendingMessageData.capture(value).restore()),
                100, clock, Executors::newSingleThreadScheduledExecutor);
    }

    private FilePendingMessageStore pending() {
        return new FilePendingMessageStore(root, 32, CLOCK);
    }

    private Path selectedPath(SelectionId id) {
        return root.resolve("selected").resolve(id.value().toString().substring(0, 2)).resolve(id.value() + ".cbor");
    }

    private static SourceId source(StandardMessage message) {
        return new SourceId(UUID.fromString(message.serial));
    }

    private static StandardMessage message(String body, String udh) {
        var message = new StandardMessage();
        message.serial = UUID.randomUUID().toString();
        message.body = body;
        message.binheader = udh;
        message.owner_id = "account";
        message.systemId = "system";
        message.from = "sender";
        message.to = "recipient";
        return message;
    }
}
