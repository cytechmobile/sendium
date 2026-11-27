package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.SelectedRouterStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import gr.cytech.sendium.core.storage.pending.PendingRecordCodec;
import gr.cytech.sendium.core.storage.pending.PendingSourceRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.cbor.CBORMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSelectedRouterStoreTest {
    @TempDir
    Path root;

    @Test
    void reopenRestoresCommittedSelectionWithoutReselecting() throws Exception {
        SelectionId id;
        try (var pending = pending(); var router = router(pending, 2)) {
            pending.open();
            router.open();
            pending.admitPart(source(1), part(1, 1), Duration.ofMinutes(1));
            pending.admitPart(source(3), part(3, 1), Duration.ofMinutes(1));
            pending.admitPart(source(2), part(2, 2), Duration.ofMinutes(1));
            pending.makeMultipartReady(Set.of(source(1), source(2)));
            assertThat(router.selectToRouter(2)).isEqualTo(1);
            var selected = router.take(Duration.ZERO).orElseThrow();
            id = selected.id();
            selected.message().body = "runtime only";
            router.release(selected);
        }
        try (var pending = pending(); var router = router(pending, 2)) {
            pending.open();
            router.open();
            var recovered = router.take(Duration.ZERO).orElseThrow();
            assertThat(recovered.id()).isEqualTo(id);
            assertThat(recovered.message().body).isEqualTo("accepted-1accepted-2");
            assertThat(recovered.sources()).containsExactlyInAnyOrder(source(1), source(2), source(3));
            assertThat(recovered.message().reassembledParts).containsExactly(source(1).value().toString(), source(2).value().toString());
            assertThat(router.selectToRouter(2)).isZero();
            assertThat(pending.find(source(1))).isPresent();
            assertThatThrownBy(() -> pending.complete(Set.of(source(1), source(3))))
                    .isInstanceOf(OutboundStorageException.class);
            pending.complete(recovered.sources());
            router.complete(recovered.id());
        }
    }

    @Test
    void interruptedSelectionLeavesCommittedOrEligibleOwners() throws Exception {
        SelectionId first;
        try (var pending = pending(); var router = router(pending, 3)) {
            pending.open();
            router.open();
            pending.admit(source(1), message(1, 3));
            pending.admit(source(2), message(2, 2));
            pending.admit(source(3), message(3, 1));
            router.selectToRouter(1);
            first = router.take(Duration.ZERO).orElseThrow().id();
            // Prevent derived ownership publication after the next authoritative commit.
            Path bucket = root.resolve("derived/selected/owners/22");
            Files.createDirectories(bucket.getParent());
            Files.writeString(bucket, "injected failure");
            assertThatThrownBy(() -> router.selectToRouter(2)).isInstanceOf(OutboundStorageException.class);
        }
        try (var pending = pending(); var router = router(pending, 3)) {
            pending.open();
            router.open();
            assertThat(router.selectToRouter(3)).isEqualTo(1);
            var owners = new HashMap<SourceId, SelectionId>();
            for (int i = 0; i < 3; i++) {
                var item = router.take(Duration.ZERO).orElseThrow();
                for (SourceId source : item.sources()) {
                    assertThat(owners.put(source, item.id())).isNull();
                }
            }
            assertThat(owners).containsEntry(source(1), first).containsKeys(source(2), source(3));
            assertThat(pending.sourcesAfter(null, 10)).hasSize(3);
        }
    }

    @Test
    void recoveredPayloadPrefetchHonorsCapacity() throws Exception {
        seedBacklog(140);
        try (var pending = pending(); var router = router(pending, 2)) {
            pending.open();
            router.open();
            assertThat(runtimeEntries(router)).hasSize(2);
            assertThat(router.take(Duration.ZERO)).isPresent();
            assertThat(router.take(Duration.ZERO)).isPresent();
            assertThat(router.take(Duration.ZERO)).isEmpty();
            assertThat(runtimeEntries(router)).hasSize(2);
            assertThat(selectedFiles()).hasSize(140);
        }
    }

    @Test
    void ownershipLookupAndRefillUseBoundedReferences() throws Exception {
        seedBacklog(140);
        try (var pending = pending(); var router = router(pending, 1)) {
            pending.open();
            router.open();
            var activeSources = FilePendingMessageStore.class.getDeclaredField("activeSources");
            activeSources.setAccessible(true);
            assertThat((Set<?>) activeSources.get(pending)).isEmpty();
            assertThat(runtimeEntries(router)).hasSize(1);
            try (var paths = Files.walk(root.resolve("derived/selected/ready"))) {
                for (Path path : paths.filter(Files::isDirectory).toList()) {
                    try (var entries = Files.list(path)) {
                        assertThat(entries.count()).isLessThanOrEqualTo(path.getFileName().toString().equals("children") ? 256 : 128);
                    }
                }
            }
            assertThat(router.selectToRouter(140)).isZero();
            int completed = 0;
            while (completed < 140) {
                var selected = router.take(Duration.ZERO).orElseThrow();
                router.markRouted(selected.id());
                router.markRouted(selected.id());
                pending.complete(selected.sources());
                router.complete(selected.id());
                assertThat(runtimeEntries(router)).isEmpty();
                completed++;
            }
            assertThat(selectedFiles()).isEmpty();
            assertThat(pending.sourcesAfter(null, 1)).isEmpty();
        }
    }

    @Test
    void truncatedCommittedSelectionFailsClosed() throws Exception {
        seedBacklog(1);
        verifyValidSelection();
        Files.write(selectedFiles().getFirst(), new byte[0]);
        try (var pending = pending(); var router = router(pending, 1)) {
            assertThatThrownBy(() -> {
                pending.open();
                router.open();
            }).isInstanceOf(OutboundStorageException.class);
            assertThat(pending.status().state()).isEqualTo(OutboundStage.State.FAILED);
            assertThat(Files.exists(acceptedPath(source(1), 2))).isTrue();
        }
    }

    @Test
    void unsafeSelectedEntryFailsBeforeProcessing() throws Exception {
        seedBacklog(1);
        verifyValidSelection();
        Path selected = selectedFiles().getFirst();
        Files.delete(selected);
        Files.createDirectory(selected);
        try (var pending = pending(); var router = router(pending, 1)) {
            assertThatThrownBy(() -> {
                pending.open();
                router.open();
            }).isInstanceOf(OutboundStorageException.class);
            assertThat(Files.exists(acceptedPath(source(1), 2))).isTrue();
        }
    }

    @Test
    void oldFormatRejectionDoesNotMutateSpool() throws Exception {
        try (var pending = pending()) {
            pending.open();
            pending.admit(source(1), message(1, 2));
        }
        byte[] accepted = Files.readAllBytes(acceptedPath(source(1), 2));
        Files.writeString(root.resolve("format"), "sendium-pending-spool:2\n");
        Path temporary = root.resolve(".tmp-recognized.part");
        Files.writeString(temporary, "retained temporary");
        try (var pending = pending()) {
            assertThatThrownBy(pending::open).isInstanceOfSatisfying(OutboundStorageException.class,
                    failure -> assertThat(failure.reason()).isEqualTo(OutboundStorageException.Reason.UNSUPPORTED));
        }
        assertThat(Files.readString(root.resolve("format"))).isEqualTo("sendium-pending-spool:2\n");
        assertThat(Files.readString(temporary)).isEqualTo("retained temporary");
        assertThat(Files.readAllBytes(acceptedPath(source(1), 2))).isEqualTo(accepted);
    }

    @Test
    void releaseIsDetachedAndCompletionRequiresPendingCleanup() throws Exception {
        try (var pending = pending(); var router = router(pending, 1)) {
            pending.open();
            router.open();
            pending.admit(source(1), message(1, 2));
            router.selectToRouter(1);
            var selected = router.take(Duration.ZERO).orElseThrow();
            assertThatThrownBy(() -> router.release(new Selected<>(selected.id(), selected.sources(), selected.message())))
                    .isInstanceOf(OutboundStorageException.class);
            selected.message().body = "released";
            router.release(selected);
            router.release(selected);
            selected.message().body = "later mutation";
            var again = router.take(Duration.ZERO).orElseThrow();
            assertThat(again.message().body).isEqualTo("released");
            assertThat(pending.find(source(1)).orElseThrow().body).isEqualTo("accepted-1");
            assertThatThrownBy(() -> router.complete(selected.id())).isInstanceOf(OutboundStorageException.class);
            router.markRouted(selected.id());
            pending.complete(selected.sources());
            router.complete(selected.id());
            router.complete(selected.id());
        }
    }

    private void seedBacklog(int count) throws Exception {
        try (var pending = pending()) {
            pending.open();
            for (int i = 1; i <= count; i++) {
                pending.admit(source(i), message(i, 2));
            }
        }
        // Physical fixture also runs on the baseline: failure is about recovery behavior, not new symbols.
        for (int i = 1; i <= count; i++) {
            writeSelected(root, new Selected<>(new SelectionId(new UUID(0, i)), Set.of(source(i)), message(i, 2)));
        }
    }

    private void verifyValidSelection() throws Exception {
        try (var pending = pending(); var router = router(pending, 1)) {
            pending.open();
            router.open();
            assertThat(router.take(Duration.ZERO)).isPresent();
        }
    }

    static void writeSelected(Path directory, Selected<StandardMessage> selected) throws Exception {
        var mapper = new CBORMapper();
        var accepted = new PendingSourceRecord(new SourceId(UUID.fromString(selected.message().serial)),
                PendingSourceRecord.Disposition.READY, PendingMessageData.capture(selected.message()));
        var node = mapper.createObjectNode();
        node.put("schemaVersion", 1);
        node.put("selectionId", selected.id().value().toString());
        var sources = node.putArray("sources");
        selected.sources().forEach(source -> sources.add(source.value().toString()));
        node.set("message", ((ObjectNode) mapper.readTree(new PendingRecordCodec().encode(accepted))).required("message"));
        Path path = selectedPath(directory, selected.id());
        Files.createDirectories(path.getParent());
        Files.write(path, mapper.writeValueAsBytes(node));
    }

    static Path selectedPath(Path directory, SelectionId id) {
        return directory.resolve("selected").resolve(id.value().toString().substring(0, 2)).resolve(id.value() + ".cbor");
    }

    @SuppressWarnings("unchecked")
    static SelectedRouterStore<StandardMessage> router(FilePendingMessageStore pending, int capacity) throws Exception {
        try {
            return (SelectedRouterStore<StandardMessage>) Class.forName("gr.cytech.sendium.core.storage.file.FileSelectedRouterStore")
                    .getConstructor(FilePendingMessageStore.class, int.class).newInstance(pending, capacity);
        } catch (ClassNotFoundException baseline) {
            return new MemorySelectedRouterStore<>(pending, capacity);
        }
    }

    private static Map<?, ?> runtimeEntries(SelectedRouterStore<StandardMessage> router) throws Exception {
        var field = router.getClass().getDeclaredField("active");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(router);
    }

    private ArrayList<Path> selectedFiles() throws Exception {
        try (var paths = Files.walk(root.resolve("selected"))) {
            return new ArrayList<>(paths.filter(path -> path.toString().endsWith(".cbor")).toList());
        }
    }

    private FilePendingMessageStore pending() {
        return new FilePendingMessageStore(root, 200);
    }

    private Path acceptedPath(SourceId source, int priority) {
        return root.resolve("accepted").resolve(Integer.toString(priority)).resolve(source.value().toString().substring(0, 2))
                .resolve(source.value() + ".cbor");
    }

    static SourceId source(int value) {
        return new SourceId(new UUID((long) (value * 17 & 255) << 56, value));
    }

    static StandardMessage message(int value, int priority) {
        var message = new StandardMessage();
        message.serial = source(value).value().toString();
        message.priority = priority;
        message.body = "accepted-" + value;
        return message;
    }

    private static StandardMessage part(int value, int ordinal) {
        var message = message(value, 2);
        message.owner_id = "account";
        message.systemId = "system";
        message.from = "sender";
        message.to = "recipient";
        message.binheader = "0500037F02%02X".formatted(ordinal);
        return message;
    }
}
