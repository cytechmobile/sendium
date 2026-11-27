package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Assignment;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.Routed;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.outbound.OutboundWork.WorkId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.RoutedWorkStore;
import gr.cytech.sendium.core.storage.memory.MemoryRoutedWorkStore;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.dataformat.cbor.CBORMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileRoutedWorkStoreTest {
    @TempDir Path root;

    @Test
    void reopenRestoresRecordedAssignment() throws Exception {
        Routed<StandardMessage> recorded;
        var assignment = assignment(1, "provider-A");
        assignment.destination().message().serial = UUID.randomUUID().toString();
        try (var pending = pending(); var routed = routed(pending, 10, 2)) {
            pending.open();
            routed.open();
            pending.admit(source(1), message(1));
            recorded = routed.record(assignment);
            var taken = routed.take("provider-A", Duration.ZERO).orElseThrow();
            taken.message().body = "volatile execution mutation";
            routed.release(taken);
        }
        try (var pending = pending(); var routed = routed(pending, 10, 2)) {
            pending.open();
            routed.open();
            var recovered = routed.take("provider-A", Duration.ZERO).orElseThrow();
            assertThat(recovered.id()).isEqualTo(recorded.id());
            assertThat(recovered.selection()).isEqualTo(assignment.selection());
            assertThat(recovered.destination()).isEqualTo("provider-A");
            assertThat(recovered.message().body).isEqualTo("routing-1");
            assertThat(recovered.message().serial).isEqualTo(assignment.destination().message().serial);
            assertThat(pending.find(source(1)).orElseThrow().body).isEqualTo("accepted-1");
            assertThat(routed.take("provider-B", Duration.ZERO)).isEmpty();
        }
    }

    @Test
    void repeatedAssignmentPreservesFirstDestination() throws Exception {
        var assignment = assignment(1, "provider-A");
        WorkId id;
        try (var pending = pending(); var routed = routed(pending, 10, 2)) {
            pending.open();
            routed.open();
            pending.admit(source(1), message(1));
            id = routed.record(assignment).id();
        }
        try (var pending = pending(); var routed = routed(pending, 10, 2)) {
            pending.open();
            routed.open();
            assignment.destination().message().body = "must not overwrite";
            var repeated = routed.record(assignment);
            assertThat(repeated.id()).isEqualTo(id);
            assertThat(repeated.message().body).isEqualTo("routing-1");
            assertThatThrownBy(() -> routed.record(assignment(1, "provider-B")))
                    .isInstanceOfSatisfying(OutboundStorageException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(OutboundStorageException.Reason.INVALID_TRANSITION));
            pending.admit(source(2), message(2));
            assertThatThrownBy(() -> routed.record(new Assignment<>(selection(1), Set.of(source(2)),
                    new Destination<>("provider-A", message(2)))))
                    .isInstanceOfSatisfying(OutboundStorageException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(OutboundStorageException.Reason.INVALID_TRANSITION));
            assertThatThrownBy(() -> routed.record(new Assignment<>(selection(2), Set.of(source(1)),
                    new Destination<>("provider-A", message(1)))))
                    .isInstanceOfSatisfying(OutboundStorageException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(OutboundStorageException.Reason.INVALID_TRANSITION));
            var taken = routed.take("provider-A", Duration.ZERO).orElseThrow();
            assertThat(taken.id()).isEqualTo(id);
            var completed = routed.complete(id);
            assertThat(routed.record(assignment).id()).isEqualTo(id);
            assertThat(routed.complete(id)).isEqualTo(completed);
            assertThat(routed.take("provider-A", Duration.ZERO)).isEmpty();
        }
    }

    @Test
    void terminalMarkerSuppressesDispatchAfterReopen() throws Exception {
        WorkId id;
        try (var pending = pending(); var routed = routed(pending, 1, 2)) {
            pending.open();
            routed.open();
            pending.admit(source(1), message(1));
            routed.record(assignment(1, "provider-A"));
            id = routed.take("provider-A", Duration.ZERO).orElseThrow().id();
            routed.complete(id);
        }
        try (var pending = pending(); var routed = routed(pending, 1, 2)) {
            pending.open();
            routed.open();
            assertThat(terminalWorkAfter(routed, null, 1)).containsExactly(id);
            assertThat(terminalWorkAfter(routed, id, 1)).isEmpty();
            assertThat(routed.take("provider-A", Duration.ZERO)).isEmpty();
            var completed = routed.complete(id);
            assertThat(completed.selection()).isEqualTo(selection(1));
            assertThat(completed.sources()).containsExactly(source(1));
            assertThatThrownBy(() -> routed.forget(completed.selection())).isInstanceOf(OutboundStorageException.class);
            pending.complete(completed.sources());
            assertThat(routed.complete(id)).isEqualTo(completed);
            pending.admit(source(2), message(2));
            assertThatThrownBy(() -> routed.record(assignment(2, "provider-A")))
                    .isInstanceOfSatisfying(OutboundStorageException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(OutboundStorageException.Reason.CAPACITY_EXCEEDED));
        }
        try (var pending = pending(); var routed = routed(pending, 1, 2)) {
            pending.open();
            routed.open();
            assertThat(terminalWorkAfter(routed, null, 1)).containsExactly(id);
            assertThat(routed.take("provider-A", Duration.ZERO)).isEmpty();
            assertThat(routed.complete(id).sources()).containsExactly(source(1));
            routed.forget(selection(1));
            routed.forget(selection(1));
            assertThat(terminalWorkAfter(routed, null, 1)).isEmpty();
            assertThat(Files.exists(stagePath(root, "routed", id))).isFalse();
            assertThat(Files.exists(stagePath(root, "routed-terminal", id))).isFalse();
            routed.record(assignment(2, "provider-A"));
            var next = routed.take("provider-A", Duration.ZERO).orElseThrow();
            pending.complete(routed.complete(next.id()).sources());
            routed.forget(next.selection());
        }
    }

    @Test
    void destinationPrefetchHonorsCapacity() throws Exception {
        seedBacklog(140);
        try (var pending = pending(); var routed = routed(pending, 2, 2)) {
            pending.open();
            routed.open();
            assertThat(active(routed)).isEmpty();
            assertThat(routed.take("provider-A", Duration.ZERO)).isPresent();
            assertThat(active(routed)).hasSize(2);
            assertThat(routed.take("provider-A", Duration.ZERO)).isPresent();
            assertThat(routed.take("provider-A", Duration.ZERO)).isEmpty();
            assertThat(active(routed)).hasSize(2);
            try (var files = Files.walk(root.resolve("routed"))) {
                assertThat(files.filter(path -> path.toString().endsWith(".cbor")).count()).isEqualTo(140);
            }
        }
    }

    @Test
    void ownershipLookupUsesBoundedDiskReferences() throws Exception {
        seedBacklog(140);
        try (var pending = pending(); var routed = routed(pending, 200, 2)) {
            pending.open();
            routed.open();
            for (int i = 1; i <= 140; i++) {
                assertThat(routed.record(assignment(i, "provider-A")).id()).isEqualTo(new WorkId(new UUID(0, i)));
            }
            assertThat(active(routed)).isEmpty();
            try (var directories = Files.walk(root.resolve("derived/routed/destinations"))) {
                for (Path directory : directories.filter(Files::isDirectory).toList()) {
                    try (var entries = Files.list(directory)) {
                        assertThat(entries.count()).isLessThanOrEqualTo(directory.getFileName().toString().equals("children") ? 256 : 128);
                    }
                }
            }
            var completed = new HashSet<WorkId>();
            for (int i = 0; i < 140; i++) {
                var work = routed.take("provider-A", Duration.ZERO).orElseThrow();
                assertThat(completed.add(work.id())).isTrue();
                var terminal = routed.complete(work.id());
                pending.complete(terminal.sources());
                routed.forget(terminal.selection());
                assertThat(active(routed).size()).isLessThanOrEqualTo(2);
            }
            assertThat(pending.sourcesAfter(null, 1)).isEmpty();
        }
    }

    @Test
    void failedPublicationDoesNotExposePartialWork() throws Exception {
        try (var pending = pending(); var routed = routed(pending, 10, 2)) {
            pending.open();
            routed.open();
            pending.admit(source(1), message(1));
            Path reference = root.resolve("derived/routed/selections");
            Files.createDirectories(reference.getParent());
            Files.writeString(reference, "injected index failure after authoritative commit");
            assertThatThrownBy(() -> routed.record(assignment(1, "provider-A"))).isInstanceOf(OutboundStorageException.class);
            assertThat(routed.status().state()).isEqualTo(OutboundStage.State.FAILED);
            assertThatThrownBy(() -> routed.take("provider-A", Duration.ZERO)).isInstanceOf(OutboundStorageException.class);
            assertThat(Files.exists(acceptedPath(root, source(1)))).isTrue();
        }
        try (var pending = pending(); var routed = routed(pending, 10, 2)) {
            pending.open();
            routed.open();
            var work = routed.take("provider-A", Duration.ZERO).orElseThrow();
            assertThat(work.selection()).isEqualTo(selection(1));
            assertThat(work.message().body).isEqualTo("routing-1");
            assertThat(routed.take("provider-A", Duration.ZERO)).isEmpty();
        }
    }

    @Test
    void releaseRetainsRuntimeMutationAndDoesNotBlockOtherDestinations() throws Exception {
        try (var pending = pending(); var routed = routed(pending, 10, 1)) {
            pending.open();
            routed.open();
            for (int i = 1; i <= 2; i++) {
                pending.admit(source(i), message(i));
                routed.record(assignment(i, i == 1 ? "provider-A" : "../provider-B|line\n"));
            }
            var taken = routed.take("provider-A", Duration.ZERO).orElseThrow();
            assertThatThrownBy(() -> pending.complete(Set.of(source(1)))).isInstanceOf(OutboundStorageException.class);
            taken.message().body = "runtime retry";
            assertThatThrownBy(() -> routed.release(new Routed<>(taken.id(), taken.selection(), taken.destination(), taken.message())))
                    .isInstanceOf(OutboundStorageException.class);
            routed.release(taken);
            routed.release(taken);
            taken.message().body = "later mutation";
            assertThat(routed.take("../provider-B|line\n", Duration.ZERO)).isPresent();
            var again = routed.take("provider-A", Duration.ZERO).orElseThrow();
            assertThat(again.message().body).isEqualTo("runtime retry");
            assertThat(routed.record(assignment(1, "provider-A")).message().body).isEqualTo("routing-1");
        }
    }

    @Test
    void interruptedForgetRetainsAuthorizationThroughTerminalDeletion() throws Exception {
        WorkId id;
        try (var pending = pending(); var routed = routed(pending, 10, 2)) {
            pending.open();
            routed.open();
            pending.admit(source(1), message(1));
            routed.record(assignment(1, "provider-A"));
            id = routed.take("provider-A", Duration.ZERO).orElseThrow().id();
            pending.complete(routed.complete(id).sources());
        }
        var mapper = new CBORMapper();
        var intent = mapper.createObjectNode();
        UUID intentId = UUID.randomUUID();
        intent.put("schemaVersion", 1);
        intent.put("intentId", intentId.toString());
        intent.putArray("sources").add(source(1).value().toString());
        intent.putArray("closedGroups");
        intent.putArray("selections");
        intent.putArray("routedWork").add(id.value().toString());
        Path authorization = root.resolve("terminal").resolve(intentId + ".cbor");
        Files.write(authorization, mapper.writeValueAsBytes(intent));
        Files.delete(stagePath(root, "routed", id));
        try (var pending = pending(); var routed = routed(pending, 10, 2)) {
            pending.open();
            routed.open();
            assertThat(routed.take("provider-A", Duration.ZERO)).isEmpty();
            assertThat(terminalWorkAfter(routed, null, 1)).isEmpty();
            assertThat(Files.exists(stagePath(root, "routed-terminal", id))).isFalse();
            assertThat(Files.exists(authorization)).isFalse();
        }
    }

    private void seedBacklog(int count) throws Exception {
        try (var pending = pending()) {
            pending.open();
            for (int i = 1; i <= count; i++) {
                pending.admit(source(i), message(i));
            }
        }
        for (int i = 1; i <= count; i++) {
            var assignment = assignment(i, "provider-A");
            writeRecord(root, new Routed<>(new WorkId(new UUID(0, i)), assignment.selection(), "provider-A", assignment.destination().message()),
                    assignment.sources());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"truncated", "unexpectedEntry", "terminalMismatch"})
    void corruptCommittedRoutedStateFailsClosed(String corruption) throws Exception {
        WorkId id;
        try (var pending = pending(); var routed = routed(pending, 10, 2)) {
            pending.open();
            routed.open();
            pending.admit(source(1), message(1));
            routed.record(assignment(1, "provider-A"));
            id = routed.take("provider-A", Duration.ZERO).orElseThrow().id();
            if (corruption.equals("terminalMismatch")) {
                routed.complete(id);
            }
        }
        if (corruption.equals("truncated")) {
            Files.write(stagePath(root, "routed", id), new byte[0]);
        } else if (corruption.equals("unexpectedEntry")) {
            Files.createDirectory(stagePath(root, "routed", id).resolveSibling("unexpected"));
        } else {
            var mapper = new CBORMapper();
            Path marker = stagePath(root, "routed-terminal", id);
            var node = (tools.jackson.databind.node.ObjectNode) mapper.readTree(Files.readAllBytes(marker));
            node.put("selectionId", selection(2).value().toString());
            Files.write(marker, mapper.writeValueAsBytes(node));
        }
        try (var pending = pending(); var routed = routed(pending, 10, 2)) {
            assertThatThrownBy(() -> {
                pending.open();
                routed.open();
            }).isInstanceOf(OutboundStorageException.class);
            assertThat(Files.exists(acceptedPath(root, source(1)))).isTrue();
        }
    }

    static void writeRecord(Path root, Routed<StandardMessage> work, Set<SourceId> sources) throws Exception {
        var mapper = new CBORMapper();
        var node = mapper.createObjectNode();
        node.put("schemaVersion", 1);
        node.put("workId", work.id().value().toString());
        node.put("selectionId", work.selection().value().toString());
        var array = node.putArray("sources");
        sources.forEach(source -> array.add(source.value().toString()));
        node.put("destination", work.destination());
        node.set("message", PendingMessageData.capture(work.message()).toNode());
        Path path = stagePath(root, "routed", work.id());
        Files.createDirectories(path.getParent());
        Files.write(path, mapper.writeValueAsBytes(node));
    }

    @SuppressWarnings("unchecked")
    private static RoutedWorkStore<StandardMessage> routed(FilePendingMessageStore pending, int capacity, int prefetch) throws Exception {
        try {
            return (RoutedWorkStore<StandardMessage>) Class.forName("gr.cytech.sendium.core.storage.file.FileRoutedWorkStore")
                    .getConstructor(FilePendingMessageStore.class, int.class, int.class).newInstance(pending, capacity, prefetch);
        } catch (ClassNotFoundException baseline) {
            return new MemoryRoutedWorkStore<>(capacity, value -> PendingMessageData.capture(value).restore());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<WorkId> terminalWorkAfter(RoutedWorkStore<StandardMessage> routed, WorkId after, int limit) throws Exception {
        try {
            return (List<WorkId>) routed.getClass().getMethod("terminalWorkAfter", WorkId.class, int.class).invoke(routed, after, limit);
        } catch (NoSuchMethodException baseline) {
            return List.of();
        }
    }

    private static Map<?, ?> active(RoutedWorkStore<StandardMessage> store) throws Exception {
        var field = store.getClass().getDeclaredField(store instanceof MemoryRoutedWorkStore<?> ? "work" : "active");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(store);
    }

    private FilePendingMessageStore pending() {
        return new FilePendingMessageStore(root, 200);
    }

    static Path stagePath(Path root, String namespace, WorkId id) {
        return root.resolve(namespace).resolve(id.value().toString().substring(0, 2)).resolve(id.value() + ".cbor");
    }

    private static Path acceptedPath(Path root, SourceId source) {
        return root.resolve("accepted/2").resolve(source.value().toString().substring(0, 2)).resolve(source.value() + ".cbor");
    }

    static Assignment<StandardMessage> assignment(int value, String destination) {
        var message = message(value);
        message.body = "routing-" + value;
        return new Assignment<>(selection(value), Set.of(source(value)), new Destination<>(destination, message));
    }

    static SelectionId selection(int value) {
        return new SelectionId(new UUID(1, value));
    }

    static SourceId source(int value) {
        return new SourceId(new UUID((long) (value * 17 & 255) << 56, value));
    }

    static StandardMessage message(int value) {
        var message = new StandardMessage();
        message.serial = source(value).value().toString();
        message.body = "accepted-" + value;
        return message;
    }
}
