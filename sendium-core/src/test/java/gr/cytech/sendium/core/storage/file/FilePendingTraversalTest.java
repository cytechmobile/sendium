package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilePendingTraversalTest {
    @TempDir Path root;

    @Test
    void drainsEveryCandidateOnceAcrossPrioritiesWithoutRemovingAcceptedFiles() {
        try (var store = store()) {
            store.open();
            var expected = new HashSet<SourceId>();
            for (int index = 0; index < 150; index++) {
                var message = message(index, index % 3 + 1);
                store.admit(source(message), message);
                expected.add(source(message));
            }
            var selected = drain(store, 150);
            assertThat(selected).hasSize(150);
            assertThat(selected.stream().flatMap(work -> work.sources().stream()).toList())
                    .containsExactlyInAnyOrderElementsOf(expected);
            assertThat(selected.stream().map(work -> work.message().priority).toList()).isSortedAccordingTo(java.util.Comparator.reverseOrder());
            assertThat(store.sourcesAfter(null, 200)).hasSize(150);
            assertThat(drain(store, 1)).isEmpty();
            for (var work : selected) {
                store.complete(work.sources());
            }
            assertThat(store.sourcesAfter(null, 200)).isEmpty();
        }
    }

    @Test
    void directCompletionAndFailedCallbackDoNotLeakReadyCountsOrRetrySnapshots() {
        try (var store = store()) {
            store.open();
            var high = message(1, 3);
            var low = message(2, 1);
            store.admit(source(high), high);
            store.admit(source(low), low);
            failHandoff(store);
            store.complete(Set.of(source(high)));
            var selected = drain(store, 2);
            assertThat(selected).singleElement().satisfies(work -> assertThat(work.sources()).containsExactly(source(low)));
            store.complete(Set.of(source(low)));
            store.complete(Set.of(source(low))); // Missing sources must not decrement counts twice.
            high.body = "new accepted content";
            store.admit(source(high), high);
            assertThat(drain(store, 2)).singleElement().satisfies(work -> assertThat(work.message().body).isEqualTo(high.body));
        }
    }

    @Test
    void failedLowPriorityCallbackDoesNotPreventNewHighPriorityWork() {
        try (var store = store()) {
            store.open();
            var low = message(1, 1);
            var high = message(2, 3);
            store.admit(source(low), low);
            failHandoff(store);
            store.admit(source(high), high);
            var selected = drain(store, 2);
            assertThat(selected).extracting(work -> work.message().priority).containsExactly(3, 1);
            assertThat(selected.getLast().message().body).isEqualTo(low.body);
        }
    }

    @Test
    void preparedAndOrdinaryWorkSharePriorityOrderingAndIdempotentCounts() {
        try (var store = store()) {
            store.open();
            var held = message(1, 2);
            var high = message(2, 3);
            var low = message(3, 1);
            store.admitHeld(source(held), held);
            store.admit(source(high), high);
            store.admit(source(low), low);
            store.makeMultipartReady(Set.of(source(held)));
            store.makeMultipartReady(Set.of(source(held)));
            var selected = drain(store, 4);
            assertThat(selected).extracting(work -> work.message().priority).containsExactly(3, 2, 1);
            store.makeMultipartReady(Set.of(source(held))); // Must not requeue already selected work.
            assertThat(drain(store, 1)).isEmpty();
            selected.forEach(work -> store.complete(work.sources()));
            store.complete(Set.of(source(held)));
            assertThat(drain(store, 1)).isEmpty();
        }
    }

    @Test
    void directCompletionOfPreparedWorkAndMutatedCallbackKeepPreparedReferencesConsistent() {
        try (var store = store()) {
            store.open();
            var held = message(1, 2);
            store.admitHeld(source(held), held);
            store.makeMultipartReady(Set.of(source(held)));
            store.complete(Set.of(source(held)));
            assertThat(drain(store, 1)).isEmpty();
            store.admitHeld(source(held), held);
            store.makeMultipartReady(Set.of(source(held)));
            failHandoff(store);
            store.selectionLock().lock();
            try {
                assertThat(store.stageNext(Instant.now(), selected -> selected.message().priority = 1)).isTrue();
                assertThat(store.stageNext(Instant.now(), selected -> { })).isFalse();
            } finally {
                store.selectionLock().unlock();
            }
            try (var files = Files.walk(root.resolve("derived/multipart/ready"))) {
                assertThat(files.filter(Files::isRegularFile).toList()).isEmpty();
            } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
            }
            store.complete(Set.of(source(held)));
            assertThat(store.status().state()).isEqualTo(OutboundStage.State.READY);
        }
    }

    @Test
    void restartRebuildsOrdinaryCountsButDiscardsPreparedOwnership() {
        var held = message(1, 3);
        var ordinary = message(2, 2);
        try (var store = store()) {
            store.open();
            store.admitHeld(source(held), held);
            store.makeMultipartReady(Set.of(source(held)));
            store.admit(source(ordinary), ordinary);
            assertThat(drain(store, 2)).hasSize(2);
        }
        try (var store = store()) {
            store.open();
            assertThat(drain(store, 2)).singleElement().satisfies(work -> assertThat(work.sources()).containsExactly(source(ordinary)));
            store.makeMultipartReady(Set.of(source(held)));
            assertThat(drain(store, 2)).singleElement().satisfies(work -> assertThat(work.sources()).containsExactly(source(held)));
        }
    }

    private static void failHandoff(FilePendingMessageStore store) {
        store.selectionLock().lock();
        try {
            assertThatThrownBy(() -> store.stageNext(Instant.now(), selected -> {
                selected.message().body = "not accepted";
                throw new IllegalStateException("test handoff failure");
            })).isInstanceOf(IllegalStateException.class);
        } finally {
            store.selectionLock().unlock();
        }
    }

    private static List<Selected<StandardMessage>> drain(FilePendingMessageStore store, int limit) {
        var selected = new ArrayList<Selected<StandardMessage>>();
        store.selectionLock().lock();
        try {
            for (int attempts = 0; attempts < limit * 4 + 100 && selected.size() < limit; attempts++) {
                store.stageNext(Instant.now(), selected::add);
            }
            return selected;
        } finally {
            store.selectionLock().unlock();
        }
    }

    private FilePendingMessageStore store() {
        return new FilePendingMessageStore(root, 200);
    }

    private static SourceId source(StandardMessage message) {
        return new SourceId(UUID.fromString(message.serial));
    }

    private static StandardMessage message(int id, int priority) {
        var message = new StandardMessage();
        message.serial = new UUID(0, id).toString();
        message.body = "accepted " + id;
        message.priority = priority;
        return message;
    }
}
