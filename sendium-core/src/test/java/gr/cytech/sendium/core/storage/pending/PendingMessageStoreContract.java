package gr.cytech.sendium.core.storage.pending;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.PendingMessageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.CAPACITY_EXCEEDED;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Backend-neutral source admission/lookup tests; durable implementations can extend this fixture. */
abstract class PendingMessageStoreContract {
    abstract PendingMessageStore<StandardMessage> createStore(int capacity);

    abstract String expectedBackend();

    abstract boolean expectedDurability();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void returnsRetrievableAcceptedHandle(boolean held) {
        try (var pending = createStore(2)) {
            pending.open();
            SourceId source = source(1);
            StandardMessage message = message(source, "accepted");
            SourceId accepted = held ? pending.admitHeld(source, message) : pending.admit(source, message);
            assertThat(accepted).isEqualTo(source);
            message.body = "changed by caller";
            assertThat(pending.find(accepted).orElseThrow().body).isEqualTo("accepted");
            StandardMessage recovered = pending.find(accepted).orElseThrow();
            recovered.body = "changed by lookup caller";
            assertThat(pending.find(accepted).orElseThrow().body).isEqualTo("accepted");
            assertThat(pending.find(source(2))).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sameSourceRetryPreservesAcceptedRecord(boolean held) {
        try (var pending = createStore(1)) {
            pending.open();
            SourceId source = source(1);
            SourceId accepted = held ? pending.admitHeld(source, message(source, "first")) : pending.admit(source, message(source, "first"));
            assertThat(pending.admit(source, message(source, "replacement"))).isEqualTo(accepted);
            assertThat(pending.admitHeld(source, message(source, "replacement held"))).isEqualTo(accepted);
            assertThat(pending.find(source).orElseThrow().body).isEqualTo("first");
            assertThat(pending.sourcesAfter(null, 2)).containsExactly(source);
            assertThatThrownBy(() -> pending.admit(source(2), message(source(2), "full")))
                    .isInstanceOfSatisfying(OutboundStorageException.class, failure -> assertThat(failure.reason()).isEqualTo(CAPACITY_EXCEEDED));
        }
    }

    @Test
    void recoveryTraversalIsBounded() {
        try (var pending = createStore(23)) {
            pending.open();
            var expected = new ArrayList<SourceId>();
            for (int index = 23; index > 0; index--) {
                SourceId id = source(index);
                expected.add(id);
                if (index % 2 == 0) {
                    pending.admitHeld(id, message(id, "held"));
                } else {
                    pending.admit(id, message(id, "ready"));
                }
            }
            expected.sort(Comparator.comparing(SourceId::value));
            var recovered = new ArrayList<SourceId>();
            SourceId cursor = null;
            for (int pageNumber = 0; pageNumber < 6; pageNumber++) {
                List<SourceId> page = pending.sourcesAfter(cursor, 5);
                assertThat(page).hasSizeLessThanOrEqualTo(5);
                if (page.isEmpty()) {
                    break;
                }
                page.forEach(id -> assertThat(pending.find(id)).isPresent());
                recovered.addAll(page);
                cursor = page.getLast();
            }
            assertThat(recovered).containsExactlyElementsOf(expected);
            assertThat(pending.sourcesAfter(cursor, 5)).isEmpty();
        }
    }

    @Test
    void removedCursorStillMakesProgress() {
        try (var pending = createStore(3)) {
            pending.open();
            for (int index = 1; index <= 3; index++) {
                pending.admit(source(index), message(source(index), "source"));
            }
            var first = pending.sourcesAfter(null, 1);
            pending.complete(Set.of(first.getFirst()));
            assertThat(pending.sourcesAfter(first.getFirst(), 2)).containsExactly(source(2), source(3));
            assertThatThrownBy(() -> pending.sourcesAfter(null, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> pending.sourcesAfter(null, -1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> first.add(source(4))).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completionOfKnownAbsentSourceIsIdempotent(boolean held) {
        try (var pending = createStore(2)) {
            pending.open();
            SourceId completed = source(1);
            SourceId unrelated = source(2);
            if (held) {
                pending.admitHeld(completed, message(completed, "held"));
            } else {
                pending.admit(completed, message(completed, "ready"));
            }
            pending.admit(unrelated, message(unrelated, "unrelated"));
            pending.complete(Set.of(completed));
            assertThat(pending.find(completed)).isEmpty();
            pending.complete(Set.of(completed));
            pending.complete(Set.of(source(3)));
            assertThat(pending.find(unrelated).orElseThrow().body).isEqualTo("unrelated");
            assertThat(pending.sourcesAfter(null, 3)).containsExactly(unrelated);
            pending.admit(source(4), message(source(4), "replacement"));
        }
    }

    @Test
    void completionRequiresReadyBackendEvenForMissingHandles() {
        try (var pending = createStore(1)) {
            assertUnavailable(() -> pending.complete(Set.of(source(1))));
            pending.open();
            pending.complete(Set.of(source(1)));
            pending.close();
            assertUnavailable(() -> pending.complete(Set.of(source(1))));
        }
    }

    @Test
    void reportsBackendIdentityAndReadiness() {
        try (var pending = createStore(1)) {
            assertThat(pending.status().backend()).isEqualTo(expectedBackend());
            assertThat(pending.status().durable()).isEqualTo(expectedDurability());
            assertThat(pending.status().state()).isEqualTo(OutboundStage.State.NEW);
            assertUnavailable(() -> pending.find(source(1)));
            assertUnavailable(() -> pending.sourcesAfter(null, 1));
            assertUnavailable(() -> pending.admit(source(1), message(source(1), "source")));
            pending.open();
            assertThat(pending.status().state()).isEqualTo(OutboundStage.State.READY);
            pending.close();
            assertThat(pending.status().state()).isEqualTo(OutboundStage.State.CLOSED);
            assertUnavailable(() -> pending.find(source(1)));
            assertUnavailable(() -> pending.sourcesAfter(null, 1));
        }
    }

    private static void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(OutboundStorageException.class, failure -> {
            assertThat(failure.stage()).isEqualTo(OutboundStage.Role.PENDING);
            assertThat(failure.reason()).isEqualTo(UNAVAILABLE);
        });
    }

    private static SourceId source(int value) {
        return new SourceId(new UUID(0, value));
    }

    private static StandardMessage message(SourceId source, String body) {
        var message = new StandardMessage();
        message.serial = source.value().toString();
        message.body = body;
        return message;
    }
}
