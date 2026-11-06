package gr.cytech.sendium.core.storage.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.file.PendingTerminalCodec.Intent;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import gr.cytech.sendium.core.storage.pending.MultipartMembership;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingTerminalRecoveryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC);
    @TempDir
    Path root;

    @Test
    void replaysPartialDeletionBeforeCheckingDuplicateOriginalsAndPreservesClosure() throws Exception {
        Group group = group();
        Intent intent = intent(group);
        new PendingTerminalJournal(root).publish(intent);
        Files.delete(sourcePath(group.first().source()));
        try (var store = store()) {
            store.open();
            assertThat(store.find(group.first().source())).isEmpty();
            assertThat(store.find(group.duplicate().source())).isEmpty();
            assertThat(store.multipartSources(group.first().groupId())).containsExactly(group.other().source());
            assertThat(Files.exists(marker(group.first().groupId()))).isTrue();
            var later = message("0500037F0303");
            assertThat(accept(store, later).groupId()).isNotEqualTo(group.first().groupId());
            assertThat(Files.exists(intentPath(intent))).isFalse();
        }
        try (var store = store()) {
            store.open();
            store.complete(Set.of(group.other().source()));
            assertThat(Files.exists(marker(group.first().groupId()))).isFalse();
            assertThat(store.multipartSources(group.first().groupId())).isEmpty();
        }
    }

    @Test
    void intentWithoutAnyPriorDeletionClosesGroupBeforeRemovingItsSources() throws Exception {
        Group group = group();
        Intent intent = intent(group);
        new PendingTerminalJournal(root).publish(intent);
        try (var store = store()) {
            store.open();
            store.complete(intent.sources());
            assertThat(store.sourcesAfter(null, 10)).containsExactly(group.other().source());
            assertThat(Files.exists(marker(group.first().groupId()))).isTrue();
        }
    }

    @Test
    void fullyDeletedIntentIsIdempotentAndLeavesNoClosureTombstoneForAnEmptyGroup() throws Exception {
        Group group = group();
        Intent intent = new Intent(UUID.randomUUID(), Set.of(group.first().source(), group.duplicate().source(), group.other().source()),
                Set.of(group.first().groupId()));
        new PendingTerminalJournal(root).publish(intent);
        for (SourceId source : intent.sources()) {
            Files.delete(sourcePath(source));
        }
        try (var store = store()) {
            store.open();
            store.complete(intent.sources());
            assertThat(store.sourcesAfter(null, 10)).isEmpty();
            assertThat(Files.exists(marker(group.first().groupId()))).isFalse();
            assertThat(Files.exists(intentPath(intent))).isFalse();
        }
    }

    @Test
    void liveTerminalCompletionRetainsClosureUntilLastIndependentOrdinalFinishes() throws Exception {
        Group group = group();
        try (var store = store(); var router = new MemorySelectedRouterStore<>(store, 2)) {
            store.open();
            router.open();
            store.makeHeldReady(Set.of(group.first().source()), store.find(group.first().source()).orElseThrow());
            store.makeHeldReady(Set.of(group.other().source()), store.find(group.other().source()).orElseThrow());
            store.complete(Set.of(group.first().source(), group.duplicate().source()));
            assertThat(Files.exists(marker(group.first().groupId()))).isTrue();
            assertThat(router.selectToRouter(2)).isEqualTo(1);
            var remaining = router.take(Duration.ZERO).orElseThrow();
            assertThat(remaining.sources()).containsExactly(group.other().source());
            store.complete(remaining.sources());
            router.complete(remaining.id());
            assertThat(store.sourcesAfter(null, 10)).isEmpty();
            assertThat(Files.exists(marker(group.first().groupId()))).isFalse();
            try (var entries = Files.list(root.resolve("terminal"))) {
                assertThat(entries).isEmpty();
            }
        }
    }

    @Test
    void rejectsIncompleteOwnedBindingsBeforePublishingAnIntentOrDeletingAnything() throws Exception {
        Group group = group();
        try (var store = store()) {
            store.open();
            assertThatThrownBy(() -> store.complete(Set.of(group.first().source())))
                    .hasMessageContaining("reason:incompleteOwnedSourceSet");
            assertThat(store.find(group.first().source())).isPresent();
            assertThat(store.find(group.duplicate().source())).isPresent();
            store.makeHeldReady(Set.of(group.first().source()), store.find(group.first().source()).orElseThrow());
            assertThatThrownBy(() -> store.complete(Set.of(group.duplicate().source())))
                    .hasMessageContaining("reason:incompleteOwnedSourceSet");
            assertThat(store.status().state()).isEqualTo(OutboundStage.State.READY);
            try (var entries = Files.list(root.resolve("terminal"))) {
                assertThat(entries).isEmpty();
            }
        }
    }

    @Test
    void journalPublicationFailureCannotRemoveAnAcceptedSource() throws Exception {
        try (var store = store()) {
            store.open();
            var message = message(null);
            SourceId source = source(message);
            store.admit(source, message);
            Files.delete(root.resolve("terminal"));
            Files.writeString(root.resolve("terminal"), "unavailable journal directory");
            assertThatThrownBy(() -> store.complete(Set.of(source))).hasMessageContaining("reason:sourceRemovalFailed");
            assertThat(Files.exists(sourcePath(source))).isTrue();
            assertThat(store.status().state()).isEqualTo(OutboundStage.State.FAILED);
        }
    }

    @Test
    void failedReplayRetainsIntentAndCanRetryAfterTheFilesystemIsRestored() throws Exception {
        Group group = group();
        Intent intent = intent(group);
        new PendingTerminalJournal(root).publish(intent);
        Files.delete(sourcePath(group.first().source()));
        byte[] duplicate = Files.readAllBytes(sourcePath(group.duplicate().source()));
        Files.delete(sourcePath(group.duplicate().source()));
        Files.createDirectory(sourcePath(group.duplicate().source()));
        assertThatThrownBy(() -> store().open()).isInstanceOf(OutboundStorageException.class);
        assertThat(Files.exists(intentPath(intent))).isTrue();
        Files.delete(sourcePath(group.duplicate().source()));
        Files.write(sourcePath(group.duplicate().source()), duplicate);
        try (var store = store()) {
            store.open();
            assertThat(store.sourcesAfter(null, 10)).containsExactly(group.other().source());
            assertThat(Files.exists(intentPath(intent))).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "unknownVersion", "identityMismatch", "missingGroupClosure"})
    void invalidCommittedIntentFailsClosedWithoutDeletingSources(String corruption) throws Exception {
        Group group = group();
        Intent intent = intent(group);
        var codec = new PendingTerminalCodec();
        byte[] bytes = codec.encode(intent);
        if (corruption.equals("empty")) {
            bytes = new byte[0];
        } else {
            var mapper = new ObjectMapper(new CBORFactory());
            var node = (ObjectNode) mapper.readTree(bytes);
            switch (corruption) {
                case "unknownVersion" -> node.put("schemaVersion", 999);
                case "identityMismatch" -> node.put("intentId", UUID.randomUUID().toString());
                case "missingGroupClosure" -> node.withArray("closedGroups").removeAll();
                default -> throw new AssertionError(corruption);
            }
            bytes = mapper.writeValueAsBytes(node);
        }
        Files.write(intentPath(intent), bytes);
        assertThatThrownBy(() -> store().open()).isInstanceOf(OutboundStorageException.class);
        assertThat(Files.exists(sourcePath(group.first().source()))).isTrue();
        assertThat(Files.exists(sourcePath(group.duplicate().source()))).isTrue();
        assertThat(Files.exists(intentPath(intent))).isTrue();
    }

    @Test
    void corruptClosureMarkerPreventsIntentReplay() throws Exception {
        Group group = group();
        Intent intent = intent(group);
        var journal = new PendingTerminalJournal(root);
        journal.publish(intent);
        journal.closeGroup(group.first().groupId());
        Files.writeString(marker(group.first().groupId()), "unknown closure version");
        assertThatThrownBy(() -> store().open()).hasMessageContaining("reason:invalidClosureMarker");
        assertThat(Files.exists(sourcePath(group.first().source()))).isTrue();
    }

    @Test
    void incompleteMetadataPublicationsAreNotCommittedIntents() throws Exception {
        Group group = group();
        Path temporary = root.resolve("terminal/.tmp-interrupted.part");
        Files.write(temporary, new byte[]{1});
        try (var store = store()) {
            store.open();
            assertThat(store.find(group.first().source())).isPresent();
            assertThat(Files.exists(temporary)).isFalse();
        }
    }

    @Test
    void drainsAllCommittedIntentsBeforeAllowingSourceIdentityReuse() throws Exception {
        Group group = group();
        var journal = new PendingTerminalJournal(root);
        journal.publish(intent(group));
        journal.publish(new Intent(UUID.randomUUID(), Set.of(group.other().source()), Set.of(group.first().groupId())));
        try (var store = store()) {
            store.open();
            assertThat(store.sourcesAfter(null, 10)).isEmpty();
            assertThat(journal.hasIntents()).isFalse();
            var replacement = message(null);
            replacement.serial = group.first().source().value().toString();
            replacement.body = "new admission after terminal completion";
            store.admit(group.first().source(), replacement);
        }
        try (var store = store()) {
            store.open();
            assertThat(store.find(group.first().source()).orElseThrow().body).isEqualTo("new admission after terminal completion");
        }
    }

    @Test
    void cannotInitializeOverAuthoritativeMetadataWithoutAFormatMarker() throws Exception {
        try (var store = store()) {
            store.open();
        }
        Files.delete(root.resolve("sources"));
        Files.delete(root.resolve("format"));
        assertThatThrownBy(() -> store().open()).hasMessageContaining("reason:missingFormatMarker");
        assertThat(Files.exists(root.resolve("format"))).isFalse();
    }

    private Group group() {
        try (var store = store()) {
            store.open();
            var first = accept(store, message("0500037F0301"));
            var duplicate = accept(store, message("0500037F0301"));
            var other = accept(store, message("0500037F0302"));
            return new Group(first, duplicate, other);
        }
    }

    private static Intent intent(Group group) {
        return new Intent(UUID.randomUUID(), Set.of(group.first().source(), group.duplicate().source()), Set.of(group.first().groupId()));
    }

    private Path sourcePath(SourceId source) {
        String id = source.value().toString();
        return root.resolve("sources").resolve(id.substring(0, 2)).resolve(id + ".cbor");
    }

    private Path intentPath(Intent intent) {
        return root.resolve("terminal").resolve(intent.id() + ".cbor");
    }

    private Path marker(UUID group) {
        return root.resolve("closed-groups").resolve(group.toString().substring(0, 2)).resolve(group + ".closed");
    }

    private FilePendingMessageStore store() {
        return new FilePendingMessageStore(root, 10, CLOCK);
    }

    private static MultipartMembership accept(FilePendingMessageStore store, StandardMessage message) {
        return store.admitPart(source(message), message, Duration.ofMinutes(1));
    }

    private static SourceId source(StandardMessage message) {
        return new SourceId(UUID.fromString(message.serial));
    }

    private static StandardMessage message(String udh) {
        var message = new StandardMessage();
        message.serial = UUID.randomUUID().toString();
        message.body = "accepted part";
        message.owner_id = "account";
        message.systemId = "system";
        message.from = "sender";
        message.to = "recipient";
        message.binheader = udh;
        return message;
    }

    private record Group(MultipartMembership first, MultipartMembership duplicate, MultipartMembership other) {}
}
