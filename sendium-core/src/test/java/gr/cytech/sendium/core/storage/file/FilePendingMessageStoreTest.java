package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.CAPACITY_EXCEEDED;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNSUPPORTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilePendingMessageStoreTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void admissionPersistsBeforeReturningAndDetachesAcceptedPayload(boolean held) {
        SourceId id = source(1);
        StandardMessage message = message(id, "accepted");
        message.attrs = new HashMap<>();
        message.attrs.put("value", new byte[]{1});
        try (var store = store(2)) {
            store.open();
            assertThat(store.status()).isEqualTo(new OutboundStage.Status("file", true, OutboundStage.State.READY));
            assertThat(held ? store.admitHeld(id, message) : store.admit(id, message)).isEqualTo(id);
            assertThat(Files.exists(path(id))).isTrue();
            message.body = "mutated";
            ((byte[]) message.attrs.get("value"))[0] = 2;
            StandardMessage found = store.find(id).orElseThrow();
            assertThat(found.body).isEqualTo("accepted");
            assertThat((byte[]) found.attrs.get("value")).containsExactly((byte) 1);
            found.body = "mutated lookup";
            assertThat(store.find(id).orElseThrow().body).isEqualTo("accepted");
            assertThat(store.find(source(2))).isEmpty();
        }
        try (var recovered = store(2)) {
            recovered.open();
            assertThat(recovered.find(id).orElseThrow().body).isEqualTo("accepted");
            assertThat(recovered.sourcesAfter(null, 1)).containsExactly(id);
        }
    }

    @Test
    void idempotentAdmissionPreservesFirstContentAndCapacityAcrossRestart() {
        SourceId id = source(1);
        try (var store = store(1)) {
            store.open();
            store.admit(id, message(id, "first"));
            assertThat(store.admitHeld(id, message(id, "replacement"))).isEqualTo(id);
            assertFull(store, source(2));
        }
        try (var store = store(1)) {
            store.open();
            assertThat(store.admit(id, message(id, "another replacement"))).isEqualTo(id);
            assertThat(store.find(id).orElseThrow().body).isEqualTo("first");
            assertFull(store, source(2));
            store.complete(Set.of(id, source(3)));
            store.complete(Set.of(id));
            assertThat(store.find(id)).isEmpty();
            store.admit(source(2), message(source(2), "capacity freed"));
        }
        try (var recovered = store(1)) {
            recovered.open();
            assertThat(recovered.sourcesAfter(null, 10)).containsExactly(source(2));
        }
    }

    @Test
    void reducedCapacityNeverDropsRecoveredSources() {
        try (var store = store(2)) {
            store.open();
            store.admit(source(1), message(source(1), "one"));
            store.admit(source(2), message(source(2), "two"));
        }
        try (var recovered = store(1)) {
            recovered.open();
            assertThat(recovered.sourcesAfter(null, 10)).hasSize(2);
            assertFull(recovered, source(3));
            recovered.complete(Set.of(source(1)));
            assertFull(recovered, source(3));
            recovered.complete(Set.of(source(2)));
            recovered.admit(source(3), message(source(3), "new"));
        }
    }

    @Test
    void pagesAfterRemovedCursorAndReturnsImmutablePages() {
        try (var store = store(3)) {
            store.open();
            for (int i = 3; i > 0; i--) {
                store.admit(source(i), message(source(i), "source"));
            }
            List<SourceId> page = store.sourcesAfter(null, 1);
            store.complete(Set.copyOf(page));
            assertThat(store.sourcesAfter(page.getFirst(), 2)).containsExactly(source(2), source(3));
            assertThatThrownBy(() -> page.add(source(4))).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> store.sourcesAfter(null, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThat(store.status().state()).isEqualTo(OutboundStage.State.READY);
        }
    }

    @Test
    void rejectsUnsupportedPayloadsAndIdentityWithoutConsumingCapacity() {
        try (var store = store(1)) {
            store.open();
            var unsupported = message(source(1), "secret payload");
            unsupported.field1 = new Object();
            assertThatThrownBy(() -> store.admit(source(1), unsupported))
                    .isInstanceOfSatisfying(OutboundStorageException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(UNSUPPORTED))
                    .hasMessageNotContaining("secret payload");
            assertThatThrownBy(() -> store.admit(source(1), message(source(2), "mismatched")))
                    .hasMessageContaining("reason:invalidSourceIdentity");
            assertThat(store.find(source(1))).isEmpty();
            store.admit(source(1), message(source(1), "supported"));
        }
    }

    @Test
    void failedAdmissionStopsReadinessAndDoesNotReturnAcceptedHandle() throws Exception {
        try (var store = store(1)) {
            store.open();
            Files.createDirectories(path(source(1)));
            assertThatThrownBy(() -> store.admit(source(1), message(source(1), "source")))
                    .isInstanceOf(OutboundStorageException.class);
            assertThat(store.status().state()).isEqualTo(OutboundStage.State.FAILED);
            assertThatThrownBy(() -> store.find(source(1))).hasMessageContaining("reason:notReady");
        }
    }

    @Test
    void corruptCommittedStateFailsLookupInsteadOfReportingAbsence() throws Exception {
        try (var store = store(1)) {
            store.open();
            store.admit(source(1), message(source(1), "source"));
            Files.write(path(source(1)), new byte[0]);
            assertThatThrownBy(() -> store.find(source(1))).hasMessageContaining("reason:invalidCommittedRecord");
            assertThat(store.status().state()).isEqualTo(OutboundStage.State.FAILED);
        }
    }

    @Test
    void interruptedCompletionFailsClosedAndReopensWithEstablishedAbsence() throws Exception {
        try (var store = store(1)) {
            store.open();
            store.admit(source(1), message(source(1), "source"));
            FolderPendingIndex.discard(root.resolve("derived/sources"));
            Files.writeString(root.resolve("derived/sources"), "broken index");
            assertThatThrownBy(() -> store.complete(Set.of(source(1)))).hasMessageContaining("reason:sourceRemovalFailed");
            assertThat(store.status().state()).isEqualTo(OutboundStage.State.FAILED);
        }
        try (var recovered = store(1)) {
            recovered.open();
            recovered.complete(Set.of(source(1)));
            assertThat(recovered.find(source(1))).isEmpty();
            recovered.admit(source(2), message(source(2), "new"));
        }
    }

    @Test
    void serializesConcurrentAdmissionsAtTheCapacityBoundary() throws Exception {
        try (var store = store(8); var executor = Executors.newFixedThreadPool(4)) {
            store.open();
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 24; i++) {
                SourceId source = source(i);
                tasks.add(() -> {
                    try {
                        store.admit(source, message(source, "source"));
                        return true;
                    } catch (OutboundStorageException failure) {
                        assertThat(failure.reason()).isEqualTo(CAPACITY_EXCEEDED);
                        return false;
                    }
                });
            }
            int accepted = 0;
            for (var result : executor.invokeAll(tasks)) {
                if (result.get()) {
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(8);
            assertThat(store.sourcesAfter(null, 24)).hasSize(8);
        }
    }

    @Test
    void unfinishedFeaturesFailExplicitlyAndLifecycleIsSingleUse() {
        var store = store(1);
        assertThatThrownBy(() -> store.find(source(1))).hasMessageContaining("reason:notReady");
        store.open();
        store.open();
        assertThatThrownBy(() -> store.admitPart(source(1), message(source(1), "part"), Duration.ofSeconds(1)))
                .hasMessageContaining("reason:invalidIdentity");
        assertThatThrownBy(() -> store.makeHeldReady(Set.of(source(1)), message(source(1), "held")))
                .hasMessageContaining("reason:heldPublicationUnavailable");
        assertThat(store.multipartSources(UUID.randomUUID())).isEmpty();
        assertThat(store.findMultipart(source(1))).isEmpty();
        store.close();
        store.close();
        assertThat(store.status().state()).isEqualTo(OutboundStage.State.CLOSED);
        assertThatThrownBy(store::open).hasMessageContaining("reason:cannotReopen");
        assertThatThrownBy(() -> store(0)).isInstanceOf(IllegalArgumentException.class);
    }

    private FilePendingMessageStore store(int capacity) {
        return new FilePendingMessageStore(root, capacity);
    }

    private Path path(SourceId source) {
        String id = source.value().toString();
        return root.resolve("sources").resolve(id.substring(0, 2)).resolve(id + ".cbor");
    }

    private static void assertFull(FilePendingMessageStore store, SourceId source) {
        assertThatThrownBy(() -> store.admit(source, message(source, "full")))
                .isInstanceOfSatisfying(OutboundStorageException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(CAPACITY_EXCEEDED));
        assertThat(store.status().state()).isEqualTo(OutboundStage.State.READY);
    }

    private static SourceId source(long id) {
        return new SourceId(new UUID(0, id));
    }

    private static StandardMessage message(SourceId source, String body) {
        var message = new StandardMessage();
        message.serial = source.value().toString();
        message.body = body;
        return message;
    }
}
