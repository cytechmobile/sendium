package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import gr.cytech.sendium.core.storage.pending.PendingMultipartRecovery;
import gr.cytech.sendium.core.storage.pending.PendingPublicationCodec;
import gr.cytech.sendium.core.storage.pending.PendingPublicationCodec.Publication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.INVALID_TRANSITION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileHeldPublicationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC);
    @TempDir
    Path root;

    @Test
    void firstBindingIsDetachedAndRemainsIdempotentAfterRouting() throws Exception {
        try (var store = store(); var router = new MemorySelectedRouterStore<>(store, 1)) {
            store.open();
            router.open();
            var a = message("original a", null);
            var b = message("original b", null);
            store.admitHeld(source(a), a);
            store.admitHeld(source(b), b);
            Set<SourceId> sources = Set.of(source(a), source(b));
            var execution = new StandardMessage(); // No accepted-source UUID requirement for execution payloads.
            execution.body = "first publication";
            store.makeHeldReady(sources, execution);
            execution.body = "caller mutation";
            var ignoredReplacement = new StandardMessage();
            ignoredReplacement.field1 = new Object();
            store.makeHeldReady(sources, ignoredReplacement);
            assertThat(router.selectToRouter(10)).isEqualTo(1);
            var selected = router.take(Duration.ZERO).orElseThrow();
            assertThat(selected.sources()).containsExactlyInAnyOrderElementsOf(sources);
            assertThat(selected.message().body).isEqualTo("first publication");
            assertThat(store.find(source(a)).orElseThrow().body).isEqualTo("original a");
            router.markRouted(selected.id());
            store.makeHeldReady(sources, ignoredReplacement);
            assertThat(router.selectToRouter(10)).isZero();
            assertThatThrownBy(() -> store.makeHeldReady(Set.of(source(a)), execution))
                    .isInstanceOfSatisfying(OutboundStorageException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(INVALID_TRANSITION));
            assertThatThrownBy(() -> store.complete(sources)).hasMessageContaining("reason:boundCompletionUnavailable");
        }
    }

    @Test
    void completeMultipartPublicationIncludesUnnotifiedDuplicatesAndUsesOneSlot() throws Exception {
        try (var store = store(); var router = new MemorySelectedRouterStore<>(store, 1)) {
            store.open();
            router.open();
            var first = message("one", "0500037F0201");
            var duplicate = message("not used", "0500037F0201");
            var second = message("two", "0500037F0202");
            var group = store.admitPart(source(first), first, Duration.ofMinutes(1));
            store.admitPart(source(duplicate), duplicate, Duration.ofMinutes(1));
            store.admitPart(source(second), second, Duration.ofMinutes(1));
            var assembled = PendingMultipartRecovery.assemble(List.of(store.find(source(first)).orElseThrow(),
                    store.find(source(second)).orElseThrow()));
            store.makeHeldReady(Set.of(source(first), source(second)), assembled);
            assertThat(router.selectToRouter(10)).isEqualTo(1);
            var selected = router.take(Duration.ZERO).orElseThrow();
            assertThat(selected.sources()).containsExactlyInAnyOrder(source(first), source(duplicate), source(second));
            assertThat(selected.message().body).isEqualTo("onetwo");
            assertThat(selected.message().binheader).isNull();
            assertThat(store.find(source(first)).orElseThrow().binheader).isEqualTo("0500037F0201");
            store.makeHeldReady(Set.of(source(first), source(second)), assembled);
            assertThat(router.selectToRouter(10)).isZero();
            var later = message("new group", "0500037F0201");
            assertThat(store.admitPart(source(later), later, Duration.ofMinutes(1)).groupId()).isNotEqualTo(group.groupId());
        }
    }

    @Test
    void independentOrdinalPublicationIncludesOnlyItsOwnDuplicateHandles() throws Exception {
        try (var store = store(); var router = new MemorySelectedRouterStore<>(store, 3)) {
            store.open();
            router.open();
            var a = message("one", "0500037F0301");
            var duplicate = message("duplicate", "0500037F0301");
            var b = message("two", "0500037F0302");
            for (var message : List.of(a, duplicate, b)) {
                store.admitPart(source(message), message, Duration.ofMinutes(1));
            }
            assertThatThrownBy(() -> store.makeHeldReady(Set.of(source(a), source(b)), a))
                    .hasMessageContaining("reason:conflictingSourceBinding");
            store.makeHeldReady(Set.of(source(a)), a);
            store.makeHeldReady(Set.of(source(b)), b);
            assertThat(router.selectToRouter(3)).isEqualTo(2);
            var first = router.take(Duration.ZERO).orElseThrow();
            var second = router.take(Duration.ZERO).orElseThrow();
            Set<Set<SourceId>> actual = Set.of(first.sources(), second.sources());
            assertThat(actual).containsExactlyInAnyOrder(Set.of(source(a), source(duplicate)), Set.of(source(b)));
            assertThat(first.message().binheader).isNotNull();
            assertThat(second.message().binheader).isNotNull();
        }
    }

    @Test
    void publicationExpandsDuplicateOwnershipAcrossPagesEvenWhenRepresentativeIsADuplicate() throws Exception {
        try (var store = new FilePendingMessageStore(root, 140, CLOCK); var router = new MemorySelectedRouterStore<>(store, 1)) {
            store.open();
            router.open();
            var original = message("original body", "0500037F0301");
            original.serial = new UUID(Long.MAX_VALUE, Long.MAX_VALUE).toString();
            store.admitPart(source(original), original, Duration.ofMinutes(1));
            for (int i = 0; i < 139; i++) {
                var duplicate = message("not independently routed", "0500037F0301");
                duplicate.serial = new UUID(0, i).toString();
                store.admitPart(source(duplicate), duplicate, Duration.ofMinutes(1));
            }
            store.makeHeldReady(Set.of(source(original)), original);
            assertThat(router.selectToRouter(1)).isEqualTo(1);
            var selected = router.take(Duration.ZERO).orElseThrow();
            assertThat(selected.sources()).hasSize(140).contains(source(original));
            assertThat(selected.message().body).isEqualTo("original body");
            store.makeHeldReady(Set.of(source(original)), original);
            assertThat(router.selectToRouter(1)).isZero();
        }
    }

    @Test
    void rejectsMissingOrdinaryDuplicateOnlyAndMixedGroupBindings() {
        try (var store = store()) {
            store.open();
            var a = message("one", "0500037F0301");
            var duplicate = message("duplicate", "0500037F0301");
            var other = message("other", "050003800301");
            var unassigned = message("held", null);
            var ordinary = message("ready", null);
            for (var message : List.of(a, duplicate, other)) {
                store.admitPart(source(message), message, Duration.ofMinutes(1));
            }
            store.admitHeld(source(unassigned), unassigned);
            store.admit(source(ordinary), ordinary);
            for (var sources : List.of(Set.of(source(duplicate)), Set.of(source(a), source(other)),
                    Set.of(source(a), source(unassigned)), Set.of(source(ordinary)), Set.of(new SourceId(UUID.randomUUID())))) {
                assertThatThrownBy(() -> store.makeHeldReady(sources, a)).hasMessageContaining("reason:conflictingSourceBinding");
            }
            assertThatThrownBy(() -> store.makeHeldReady(Set.of(), a)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void publicationIsDiscardedOnRestartAndOriginalHeldSourcesCanBeReconstructed() throws Exception {
        var message = message("accepted", null);
        try (var store = store()) {
            store.open();
            store.admitHeld(source(message), message);
            store.makeHeldReady(Set.of(source(message)), message("old execution", null));
        }
        try (var store = store(); var router = new MemorySelectedRouterStore<>(store, 1)) {
            store.open();
            router.open();
            assertThat(router.selectToRouter(1)).isZero();
            assertThat(store.find(source(message)).orElseThrow().body).isEqualTo("accepted");
            store.makeHeldReady(Set.of(source(message)), message("reconstructed", null));
            assertThat(router.selectToRouter(1)).isEqualTo(1);
            assertThat(router.take(Duration.ZERO).orElseThrow().message().body).isEqualTo("reconstructed");
        }
    }

    @Test
    void interruptedSourceBindingsCannotTurnIntoPartiallyReadyWorkOnRestart() throws Exception {
        var a = message("a", null);
        var b = message("b", null);
        try (var store = store()) {
            store.open();
            store.admitHeld(source(a), a);
            store.admitHeld(source(b), b);
        }
        Path publications = root.resolve("derived/publications");
        var publication = new Publication(UUID.randomUUID(), Set.of(source(a), source(b)), PendingMessageData.capture(a));
        Files.write(publications.resolve("work").resolve(publication.id() + ".cbor"), new PendingPublicationCodec().encode(publication));
        String id = source(a).value().toString();
        Path binding = publications.resolve("bindings").resolve(id.substring(0, 2)).resolve(id);
        Files.createDirectories(binding.getParent());
        Files.writeString(binding, publication.id().toString()); // Interrupted before binding b or publishing ready reference.
        try (var store = store(); var router = new MemorySelectedRouterStore<>(store, 2)) {
            store.open();
            router.open();
            assertThat(router.selectToRouter(2)).isZero();
            assertThat(store.sourcesAfter(null, 10)).containsExactlyInAnyOrder(source(a), source(b));
            store.makeHeldReady(Set.of(source(a), source(b)), a);
            assertThat(router.selectToRouter(2)).isEqualTo(1);
            assertThat(router.take(Duration.ZERO).orElseThrow().sources()).containsExactlyInAnyOrder(source(a), source(b));
        }
    }

    @Test
    void indexFailureAfterBindingsFailsClosedAndLeavesSourcesRecoverable() throws Exception {
        var message = message("accepted", null);
        try (var store = store(); var router = new MemorySelectedRouterStore<>(store, 1)) {
            store.open();
            router.open();
            store.admitHeld(source(message), message);
            Files.delete(root.resolve("derived/priorities"));
            Files.writeString(root.resolve("derived/priorities"), "broken index");
            assertThatThrownBy(() -> store.makeHeldReady(Set.of(source(message)), message))
                    .hasMessageContaining("reason:bindingPublicationFailed");
            assertThat(store.status().state()).isEqualTo(OutboundStage.State.FAILED);
            assertThatThrownBy(() -> router.selectToRouter(1)).isInstanceOf(OutboundStorageException.class);
        }
        try (var store = store()) {
            store.open();
            assertThat(store.find(source(message)).orElseThrow().body).isEqualTo("accepted");
            store.makeHeldReady(Set.of(source(message)), message);
        }
    }

    @Test
    void corruptExecutionSnapshotFailsClosedWithoutLeakingItsPayload() throws Exception {
        try (var store = store(); var router = new MemorySelectedRouterStore<>(store, 1)) {
            store.open();
            router.open();
            var message = message("private payload", null);
            store.admitHeld(source(message), message);
            store.makeHeldReady(Set.of(source(message)), message);
            try (var files = Files.list(root.resolve("derived/publications/work"))) {
                Files.write(files.findFirst().orElseThrow(), new byte[0]);
            }
            assertThatThrownBy(() -> router.selectToRouter(1)).hasMessageContaining("reason:snapshotReadFailed")
                    .hasMessageNotContaining("private payload");
            assertThat(store.status().state()).isEqualTo(OutboundStage.State.FAILED);
        }
    }

    private FilePendingMessageStore store() {
        return new FilePendingMessageStore(root, 10, CLOCK);
    }

    private static SourceId source(StandardMessage message) {
        return new SourceId(UUID.fromString(message.serial));
    }

    private static StandardMessage message(String body, String udh) {
        var message = new StandardMessage();
        message.serial = UUID.randomUUID().toString();
        message.body = body;
        message.owner_id = "account";
        message.systemId = "system";
        message.from = "sender";
        message.to = "recipient";
        message.binheader = udh;
        return message;
    }
}
