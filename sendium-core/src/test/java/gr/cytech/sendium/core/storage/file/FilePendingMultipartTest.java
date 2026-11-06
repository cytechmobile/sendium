package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import gr.cytech.sendium.core.storage.pending.MultipartKey;
import gr.cytech.sendium.core.storage.pending.MultipartMembership;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import gr.cytech.sendium.core.storage.pending.PendingRecordCodec;
import gr.cytech.sendium.core.storage.pending.PendingSourceRecord;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.CAPACITY_EXCEEDED;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.INVALID_TRANSITION;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNSUPPORTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilePendingMultipartTest {
    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    @TempDir
    Path root;

    @Test
    void persistsEveryHandleAndFirstOrdinalOwnerBeforeReturning() throws Exception {
        MultipartMembership first;
        MultipartMembership duplicate;
        MultipartMembership second;
        try (var store = store(NOW, 4); var router = new MemorySelectedRouterStore<>(store, 4)) {
            store.open();
            router.open();
            first = accept(store, part("0500037F0301"));
            duplicate = accept(store, part("0500037F0301"));
            second = accept(store, part("0500037F0302"));
            assertThat(first.duplicate()).isFalse();
            assertThat(duplicate.originalSource()).isEqualTo(first.source());
            assertThat(duplicate.duplicate()).isTrue();
            assertThat(second.groupId()).isEqualTo(first.groupId());
            assertThat(store.multipartSources(first.groupId())).containsExactlyInAnyOrder(first.source(), duplicate.source(), second.source());
            assertThat(router.selectToRouter(4)).isZero();
            var codec = new PendingRecordCodec();
            for (var membership : new MultipartMembership[]{first, duplicate, second}) {
                assertThat(codec.decode(Files.readAllBytes(path(membership.source()))).multipart()).isEqualTo(membership);
            }
        }
        try (var store = store(NOW.plusSeconds(20), 4)) {
            store.open();
            assertThat(store.findMultipart(first.source())).contains(first);
            assertThat(store.findMultipart(duplicate.source())).contains(duplicate);
            assertThat(store.multipartSources(first.groupId())).containsExactlyInAnyOrder(first.source(), duplicate.source(), second.source());
            assertThat(store.multipartSources(UUID.randomUUID())).isEmpty();
        }
    }

    @Test
    void retainedSourceRetryPreservesMembershipAndPayloadAtCapacity() {
        StandardMessage original = part("0500037F0301");
        MultipartMembership membership;
        try (var store = store(NOW, 1)) {
            store.open();
            membership = accept(store, original);
            original.body = "retry must not replace";
            original.binheader = "invalid";
            assertThat(store.admitPart(membership.source(), original, Duration.ofMinutes(5))).isEqualTo(membership);
            assertThat(store.find(membership.source()).orElseThrow().body).isEqualTo("accepted part");
            assertThatThrownBy(() -> accept(store, part("0500037F0301")))
                    .isInstanceOfSatisfying(OutboundStorageException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(CAPACITY_EXCEEDED));
        }
        try (var store = store(NOW.plusSeconds(10), 1)) {
            store.open();
            assertThat(store.admitPart(membership.source(), original, TIMEOUT)).isEqualTo(membership);
            assertThat(store.sourcesAfter(null, 2)).hasSize(1);
        }
    }

    @Test
    void laterPartAfterRestartRetainsAbsoluteDeadlineAndCompleteGroupCloses() {
        MultipartMembership first;
        try (var store = store(NOW, 4)) {
            store.open();
            first = accept(store, part("0500037F0201"));
        }
        try (var store = store(NOW.plusSeconds(50), 4)) {
            store.open();
            StandardMessage message = part("0500037F0202");
            var second = store.admitPart(source(message), message, Duration.ofMinutes(5));
            assertThat(second.groupId()).isEqualTo(first.groupId());
            assertThat(second.deadline()).isEqualTo(NOW.plusSeconds(60));
            assertThat(second.groupOrder()).isEqualTo(first.groupOrder());
            var next = accept(store, part("0500037F0201"));
            assertThat(next.groupId()).isNotEqualTo(first.groupId());
            assertThat(next.groupOrder()).isGreaterThan(first.groupOrder());
        }
        try (var store = store(NOW.plusSeconds(55), 4)) {
            store.open();
            var next = accept(store, part("0500037F0202"));
            assertThat(next.groupId()).isNotEqualTo(first.groupId());
        }
    }

    @Test
    void exactDeadlineDoesNotReopenExpiredGroup() {
        MultipartMembership first;
        try (var store = store(NOW, 3)) {
            store.open();
            first = accept(store, part("0500037F0301"));
        }
        try (var store = store(first.deadline(), 3)) {
            store.open();
            var later = accept(store, part("0500037F0302"));
            assertThat(later.groupId()).isNotEqualTo(first.groupId());
            assertThat(later.deadline()).isEqualTo(first.deadline().plus(TIMEOUT));
            assertThat(store.multipartSources(first.groupId())).containsExactly(first.source());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "system", "sender", "recipient", "referenceBits", "reference", "total", "udh"})
    void isolatesAllMatchingIdentityComponents(String component) {
        try (var store = store(NOW, 2)) {
            store.open();
            var first = accept(store, part("0500037F0301"));
            var other = part("0500037F0302");
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
            assertThat(accept(store, other).groupId()).isNotEqualTo(first.groupId());
        }
    }

    @Test
    void nullAndEmptyIdentityFieldsAreDistinct() {
        try (var store = store(NOW, 2)) {
            store.open();
            var first = part("0500037F0301");
            first.systemId = null;
            var second = part("0500037F0302");
            second.systemId = "";
            assertThat(accept(store, first).groupId()).isNotEqualTo(accept(store, second).groupId());
        }
    }

    @Test
    void concurrentDuplicatesHaveOneOriginalAndEveryAcceptedHandle() throws Exception {
        try (var store = store(NOW, 24); var executor = Executors.newFixedThreadPool(4)) {
            store.open();
            var tasks = new ArrayList<java.util.concurrent.Future<MultipartMembership>>();
            for (int i = 0; i < 24; i++) {
                tasks.add(executor.submit(() -> accept(store, part("0500037F0301"))));
            }
            var groups = new HashSet<UUID>();
            var originals = new HashSet<SourceId>();
            int nonDuplicates = 0;
            for (var result : tasks) {
                MultipartMembership member = result.get(5, TimeUnit.SECONDS);
                groups.add(member.groupId());
                originals.add(member.originalSource());
                nonDuplicates += member.duplicate() ? 0 : 1;
            }
            assertThat(groups).hasSize(1);
            assertThat(originals).hasSize(1);
            assertThat(nonDuplicates).isEqualTo(1);
            assertThat(store.multipartSources(groups.iterator().next())).hasSize(24);
        }
    }

    @Test
    void duplicateGroupsCrossReferenceLeafAndPageBoundsWithoutLoadingPayloadBacklog() {
        MultipartMembership first;
        try (var store = store(NOW, 140)) {
            store.open();
            first = accept(store, part("0500037F0301"));
            for (int i = 1; i < 140; i++) {
                assertThat(accept(store, part("0500037F0301")).originalSource()).isEqualTo(first.source());
            }
            assertThat(store.multipartSources(first.groupId())).hasSize(140).doesNotHaveDuplicates();
        }
        try (var store = store(NOW.plusSeconds(10), 140)) {
            store.open();
            assertThat(store.multipartSources(first.groupId())).hasSize(140).contains(first.source());
        }
    }

    @Test
    void choosesOldestRecordedEligibleGroupWithoutDependingOnDirectoryOrder() {
        UUID older = UUID.randomUUID();
        try (var spool = new PendingSpool(root)) {
            spool.open();
            for (long order : new long[]{9, 3}) {
                var message = part("0500037F0301");
                var member = new MultipartMembership(source(message), order == 3 ? older : UUID.randomUUID(),
                        MultipartKey.from(message), 1, NOW.plus(TIMEOUT), order);
                spool.publish(new PendingSourceRecord(source(message), PendingSourceRecord.Disposition.HELD,
                        PendingMessageData.capture(message), member));
            }
        }
        try (var store = store(NOW, 3)) {
            store.open();
            var second = accept(store, part("0500037F0302"));
            assertThat(second.groupId()).isEqualTo(older);
            assertThat(second.groupOrder()).isEqualTo(3);
        }
    }

    @Test
    void missingOrdinalOwnerDuringRuntimeCannotBeAcknowledgedAsAnotherDuplicate() throws Exception {
        try (var store = store(NOW, 3)) {
            store.open();
            var original = accept(store, part("0500037F0301"));
            // The next ordinal keeps a valid representative while the first ordinal disappears.
            accept(store, part("0500037F0302"));
            Files.delete(path(original.source()));
            var message = part("0500037F0301");
            assertThatThrownBy(() -> accept(store, message)).isInstanceOf(OutboundStorageException.class);
            assertThat(store.status().state()).isEqualTo(OutboundStage.State.FAILED);
            assertThat(Files.exists(path(source(message)))).isFalse();
        }
    }

    @Test
    void malformedPartAndInvalidTimeoutDoNotConsumeCapacity() {
        try (var store = store(NOW, 1)) {
            store.open();
            var invalid = part("0500037F0300");
            assertThatThrownBy(() -> accept(store, invalid)).isInstanceOfSatisfying(OutboundStorageException.class,
                    failure -> assertThat(failure.reason()).isEqualTo(UNSUPPORTED));
            assertThat(store.find(source(invalid))).isEmpty();
            var valid = part("0500037F0301");
            assertThatThrownBy(() -> store.admitPart(source(valid), valid, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
            assertThat(accept(store, valid)).isNotNull();
        }
    }

    @Test
    void cannotConvertOrdinarySourceIntoMultipartMembership() {
        try (var store = store(NOW, 1)) {
            store.open();
            var message = part("0500037F0301");
            store.admit(source(message), message);
            assertThatThrownBy(() -> accept(store, message)).isInstanceOfSatisfying(OutboundStorageException.class,
                    failure -> assertThat(failure.reason()).isEqualTo(INVALID_TRANSITION));
        }
    }

    @Test
    void missingOriginalFailsRecoveryWithoutDeletingDuplicate() throws Exception {
        MultipartMembership original;
        MultipartMembership duplicate;
        try (var store = store(NOW, 2)) {
            store.open();
            original = accept(store, part("0500037F0301"));
            duplicate = accept(store, part("0500037F0301"));
        }
        Files.delete(path(original.source()));
        assertThatThrownBy(() -> store(NOW, 2).open()).hasMessageContaining("reason:invalidMembership");
        assertThat(Files.exists(path(duplicate.source()))).isTrue();
    }

    @Test
    void conflictingSavedDeadlineFailsRecovery() throws Exception {
        MultipartMembership original;
        MultipartMembership duplicate;
        try (var store = store(NOW, 2)) {
            store.open();
            original = accept(store, part("0500037F0301"));
            duplicate = accept(store, part("0500037F0301"));
        }
        var codec = new PendingRecordCodec();
        var record = codec.decode(Files.readAllBytes(path(duplicate.source())));
        var changed = new MultipartMembership(duplicate.source(), duplicate.groupId(), duplicate.key(), duplicate.ordinal(),
                duplicate.deadline().plusSeconds(1), duplicate.groupOrder(), original.source());
        Files.write(path(duplicate.source()), codec.encode(new PendingSourceRecord(record.source(), record.disposition(), record.message(), changed)));
        assertThatThrownBy(() -> store(NOW, 2).open()).hasMessageContaining("reason:invalidMembership");
    }

    @Test
    void failedGroupIndexUpdateDoesNotAcknowledgeButCommittedMembershipRecovers() throws Exception {
        var message = part("0500037F0301");
        try (var store = store(NOW, 2)) {
            store.open();
            Files.delete(root.resolve("derived/multipart/groups"));
            Files.writeString(root.resolve("derived/multipart/groups"), "broken index");
            assertThatThrownBy(() -> accept(store, message)).hasMessageContaining("reason:publicationFailed");
            assertThat(store.status().state()).isEqualTo(OutboundStage.State.FAILED);
            assertThat(Files.exists(path(source(message)))).isTrue();
        }
        try (var store = store(NOW.plusSeconds(20), 2)) {
            store.open();
            var recovered = store.findMultipart(source(message)).orElseThrow();
            assertThat(recovered.deadline()).isEqualTo(NOW.plus(TIMEOUT));
            assertThat(accept(store, part("0500037F0302")).groupId()).isEqualTo(recovered.groupId());
        }
    }

    @Test
    void multipartCompletionRemainsExplicitlyBlockedUntilGroupedCleanupExists() {
        try (var store = store(NOW, 1)) {
            store.open();
            var member = accept(store, part("0500037F0301"));
            assertThatThrownBy(() -> store.complete(Set.of(member.source()))).hasMessageContaining("reason:multipartCompletionUnavailable");
            assertThat(store.findMultipart(member.source())).contains(member);
            assertThat(store.status().state()).isEqualTo(OutboundStage.State.READY);
        }
    }

    private FilePendingMessageStore store(Instant now, int capacity) {
        return new FilePendingMessageStore(root, capacity, Clock.fixed(now, ZoneOffset.UTC));
    }

    private Path path(SourceId source) {
        String id = source.value().toString();
        return root.resolve("sources").resolve(id.substring(0, 2)).resolve(id + ".cbor");
    }

    private static MultipartMembership accept(FilePendingMessageStore store, StandardMessage message) {
        return store.admitPart(source(message), message, TIMEOUT);
    }

    private static SourceId source(StandardMessage message) {
        return new SourceId(UUID.fromString(message.serial));
    }

    private static StandardMessage part(String udh) {
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
}
