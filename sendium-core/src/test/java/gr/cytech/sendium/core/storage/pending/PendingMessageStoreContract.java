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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.CAPACITY_EXCEEDED;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Backend-neutral source admission/lookup tests; durable implementations can extend this fixture. */
abstract class PendingMessageStoreContract {
    abstract PendingMessageStore<StandardMessage> createStore(int capacity);

    abstract PendingMessageStore<StandardMessage> createStore(int capacity, Clock clock);

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
    void repeatedOrdinalOwnsDistinctSource() {
        try (var pending = createStore(2)) {
            pending.open();
            var original = multipart("0500037F0301");
            var first = acceptPart(pending, original);
            var repeated = acceptPart(pending, multipart("0500037F0301"));
            assertThat(repeated.source()).isNotEqualTo(first.source());
            assertThat(repeated.groupId()).isNotEqualTo(first.groupId());
            var retry = multipart("0500037F0302");
            retry.serial = original.serial;
            retry.body = "replacement must not win";
            assertThat(pending.admitPart(first.source(), retry, Duration.ofMinutes(5))).isEqualTo(first);
            assertThat(pending.find(first.source()).orElseThrow().binheader).isEqualTo("0500037F0301");
            assertThat(pending.findMultipart(first.source())).contains(first);
            assertThat(pending.findMultipart(repeated.source())).contains(repeated);
            assertThat(pending.sourcesAfter(null, 3)).hasSize(2);
            assertThatThrownBy(() -> acceptPart(pending, multipart("0500037F0303")))
                    .isInstanceOfSatisfying(OutboundStorageException.class, failure -> assertThat(failure.reason()).isEqualTo(CAPACITY_EXCEEDED));
        }
    }

    @Test
    void assignsOldestEligibleGroup() {
        var clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);
        try (var pending = createStore(5, clock)) {
            pending.open();
            var a = acceptPart(pending, multipart("0500037F0301"));
            var b = acceptPart(pending, multipart("0500037F0301"));
            assertThat(a.groupOrder()).isLessThan(b.groupOrder());
            var c = acceptPart(pending, multipart("0500037F0302"));
            var d = acceptPart(pending, multipart("0500037F0303"));
            assertThat(c.groupId()).isEqualTo(a.groupId()).isNotEqualTo(b.groupId());
            assertThat(d.groupId()).isEqualTo(a.groupId());
            assertThat(c.groupOrder()).isEqualTo(a.groupOrder());
            assertThat(d.groupOrder()).isEqualTo(a.groupOrder());
            var next = acceptPart(pending, multipart("0500037F0302"));
            assertThat(next.groupId()).isEqualTo(b.groupId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "system", "sender", "recipient", "referenceBits", "reference", "total", "udh"})
    void isolatesMultipartIdentityComponents(String component) {
        try (var pending = createStore(2)) {
            pending.open();
            var first = acceptPart(pending, multipart("0500037F0301"));
            var other = multipart("0500037F0302");
            switch (component) {
                case "account" -> other.owner_id = "another-account";
                case "system" -> other.systemId = "another-system";
                case "sender" -> other.from = "another-sender";
                case "recipient" -> other.to = "another-recipient";
                case "referenceBits" -> other.binheader = "060804007F0302";
                case "reference" -> other.binheader = "050003800302";
                case "total" -> other.binheader = "0500037F0402";
                case "udh" -> other.binheader = "0B00037F0302050423F023F1";
                default -> throw new AssertionError(component);
            }
            assertThat(acceptPart(pending, other).groupId()).isNotEqualTo(first.groupId());
        }
    }

    @Test
    void laterPartPreservesAbsoluteDeadline() {
        var clock = new MutableClock();
        try (var pending = createStore(2, clock)) {
            pending.open();
            var first = acceptPart(pending, multipart("0500037F0301"));
            clock.now.set(clock.instant().plusSeconds(50));
            StandardMessage second = multipart("0500037F0302");
            SourceId source = new SourceId(UUID.fromString(second.serial));
            var later = pending.admitPart(source, second, Duration.ofMinutes(5));
            assertThat(later.groupId()).isEqualTo(first.groupId());
            assertThat(later.deadline()).isEqualTo(Instant.parse("2026-10-20T12:01:00Z"));
        }
    }

    @Test
    void doesNotJoinExpiredGroup() {
        var clock = new MutableClock();
        try (var pending = createStore(2, clock)) {
            pending.open();
            var first = acceptPart(pending, multipart("0500037F0301"));
            clock.now.set(first.deadline());
            var second = acceptPart(pending, multipart("0500037F0302"));
            assertThat(second.groupId()).isNotEqualTo(first.groupId());
            assertThat(second.deadline()).isEqualTo(first.deadline().plusSeconds(60));
        }
    }

    @Test
    void assignsConcurrentOrdinalsAtomically() throws Exception {
        try (var pending = createStore(32); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            pending.open();
            var tasks = new ArrayList<java.util.concurrent.Future<MultipartMembership>>();
            for (int index = 0; index < 32; index++) {
                tasks.add(executor.submit(() -> acceptPart(pending, multipart("0500037F0301"))));
            }
            var groups = new java.util.HashSet<UUID>();
            for (var task : tasks) {
                var accepted = task.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(groups.add(accepted.groupId())).isTrue();
                assertThat(pending.findMultipart(accepted.source())).contains(accepted);
            }
            assertThat(pending.sourcesAfter(null, 32)).hasSize(32);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publishedOrCompletedGroupCannotAbsorbAnotherPart(boolean completed) {
        try (var pending = createStore(2)) {
            pending.open();
            var first = acceptPart(pending, multipart("0500037F0301"));
            if (completed) {
                pending.complete(Set.of(first.source()));
            } else {
                pending.makeHeldReady(Set.of(first.source()), pending.find(first.source()).orElseThrow());
            }
            var later = acceptPart(pending, multipart("0500037F0302"));
            assertThat(later.groupId()).isNotEqualTo(first.groupId());
        }
    }

    @Test
    void malformedPartDoesNotConsumeCapacityOrCreateAcceptedMembership() {
        try (var pending = createStore(1)) {
            pending.open();
            StandardMessage invalid = multipart("0500037F0300");
            SourceId source = new SourceId(UUID.fromString(invalid.serial));
            assertThatThrownBy(() -> pending.admitPart(source, invalid, Duration.ofSeconds(60)))
                    .isInstanceOfSatisfying(OutboundStorageException.class, failure ->
                            assertThat(failure.reason()).isEqualTo(OutboundStorageException.Reason.UNSUPPORTED));
            assertThat(pending.find(source)).isEmpty();
            assertThat(pending.findMultipart(source)).isEmpty();
            assertThat(pending.sourcesAfter(null, 1)).isEmpty();
            assertThat(acceptPart(pending, multipart("0500037F0301"))).isNotNull();
        }
    }

    @Test
    void publicationCannotMergeSeparateOrIncompleteAssignedGroups() {
        try (var pending = createStore(4)) {
            pending.open();
            var first = acceptPart(pending, multipart("0500037F0301"));
            var repeated = acceptPart(pending, multipart("0500037F0301"));
            var second = acceptPart(pending, multipart("0500037F0302"));
            assertThatThrownBy(() -> pending.makeHeldReady(Set.of(first.source(), repeated.source(), second.source()), new StandardMessage()))
                    .isInstanceOfSatisfying(OutboundStorageException.class, failure ->
                            assertThat(failure.reason()).isEqualTo(OutboundStorageException.Reason.INVALID_TRANSITION));
            assertThatThrownBy(() -> pending.makeHeldReady(Set.of(first.source(), second.source()), new StandardMessage()))
                    .isInstanceOf(OutboundStorageException.class);
            var third = acceptPart(pending, multipart("0500037F0303"));
            pending.makeHeldReady(Set.of(first.source(), second.source(), third.source()), new StandardMessage());
            assertThat(pending.findMultipart(repeated.source())).contains(repeated);
        }
    }

    private static MultipartMembership acceptPart(PendingMessageStore<StandardMessage> pending, StandardMessage message) {
        return pending.admitPart(new SourceId(UUID.fromString(message.serial)), message, Duration.ofSeconds(60));
    }

    private static StandardMessage multipart(String udh) {
        var message = new StandardMessage();
        message.serial = UUID.randomUUID().toString();
        message.owner_id = "account";
        message.systemId = "system";
        message.from = "sender";
        message.to = "recipient";
        message.body = "accepted part";
        message.binheader = udh;
        return message;
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-20T12:00:00Z"));

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
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
            assertUnavailable(() -> pending.findMultipart(source(1)));
            assertUnavailable(() -> pending.admit(source(1), message(source(1), "source")));
            pending.open();
            assertThat(pending.status().state()).isEqualTo(OutboundStage.State.READY);
            pending.close();
            assertThat(pending.status().state()).isEqualTo(OutboundStage.State.CLOSED);
            assertUnavailable(() -> pending.find(source(1)));
            assertUnavailable(() -> pending.sourcesAfter(null, 1));
            assertUnavailable(() -> pending.findMultipart(source(1)));
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
