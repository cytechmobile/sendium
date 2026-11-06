package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import gr.cytech.sendium.core.storage.pending.PendingSourceRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static gr.cytech.sendium.core.storage.pending.PendingSourceRecord.Disposition.HELD;
import static gr.cytech.sendium.core.storage.pending.PendingSourceRecord.Disposition.READY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingSpoolIndexTest {
    @TempDir
    Path root;

    @Test
    void pagesInJavaUuidOrderIncludingBothSignedLongBoundaries() {
        var ids = new ArrayList<SourceId>();
        var random = new Random(339);
        for (int i = 0; i < 300; i++) {
            ids.add(new SourceId(new UUID(random.nextLong(), random.nextLong())));
        }
        for (long most : new long[]{Long.MIN_VALUE, -1, 0, Long.MAX_VALUE}) {
            for (long least : new long[]{Long.MIN_VALUE, -1, 0, Long.MAX_VALUE}) {
                ids.add(new SourceId(new UUID(most, least)));
            }
        }
        try (var spool = new PendingSpool(root)) {
            spool.open();
            for (SourceId id : ids) {
                spool.publish(record(id, 0, READY));
            }
            assertPages(spool, ids);
        }
        try (var spool = new PendingSpool(root)) {
            spool.open();
            assertPages(spool, ids);
            assertThat(spool.recoveredSources()).isEqualTo(ids.size());
        }
    }

    @Test
    void selectsHighestSignedPriorityAndExcludesHeldSources() {
        var records = new ArrayList<PendingSourceRecord>();
        try (var spool = new PendingSpool(root)) {
            spool.open();
            for (int priority : new int[]{0, Integer.MIN_VALUE, -1, Integer.MAX_VALUE, 1}) {
                PendingSourceRecord record = record(new SourceId(UUID.randomUUID()), priority, READY);
                records.add(record);
                spool.publish(record);
            }
            spool.publish(record(new SourceId(UUID.randomUUID()), Integer.MAX_VALUE, HELD));
            List<SourceId> expected = records.stream()
                    .sorted(Comparator.comparingInt((PendingSourceRecord r) -> r.message().restore().priority).reversed())
                    .map(PendingSourceRecord::source).toList();
            assertThat(spool.readyCandidates(2)).containsExactlyElementsOf(expected.subList(0, 2));
            assertThat(spool.readyCandidates(20)).containsExactlyElementsOf(expected);
            for (PendingSourceRecord record : records) {
                spool.removeReady(record.source(), record.message().restore().priority);
            }
            assertThat(spool.readyCandidates(20)).isEmpty();
            assertThat(spool.sourcesAfter(null, 20)).hasSize(6);
            // Idempotent admission must not requeue a source already selected in this process.
            spool.publish(records.getFirst());
            assertThat(spool.readyCandidates(1)).isEmpty();
        }
        try (var spool = new PendingSpool(root)) {
            spool.open();
            assertThat(spool.readyCandidates(20)).hasSize(5);
        }
    }

    @Test
    void refillsWithoutReadingSourcePayloadsAndObservesNewHigherPriorityWork() throws Exception {
        try (var spool = new PendingSpool(root)) {
            spool.open();
            var low = record(new SourceId(UUID.randomUUID()), 1, READY);
            spool.publish(low);
            String id = low.source().value().toString();
            // Proves candidate traversal uses references, not repeated payload scans.
            Files.write(root.resolve("sources").resolve(id.substring(0, 2)).resolve(id + ".cbor"), new byte[0]);
            assertThat(spool.readyCandidates(1)).containsExactly(low.source());
            var high = record(new SourceId(UUID.randomUUID()), 10, READY);
            spool.publish(high);
            assertThat(spool.readyCandidates(1)).containsExactly(high.source());
            spool.removeReady(high.source(), 10);
            assertThat(spool.readyCandidates(1)).containsExactly(low.source());
        }
        assertThatThrownBy(() -> new PendingSpool(root).open()).hasMessageContaining("invalidCommittedRecord");
    }

    @Test
    void rebuildsDiscardedOrInterruptedDerivedStateWithoutLosingSources() throws Exception {
        SourceId source = new SourceId(UUID.randomUUID());
        try (var spool = new PendingSpool(root)) {
            spool.open();
            spool.publish(record(source, 2, READY));
        }
        FolderPendingIndex.discard(root.resolve("derived"));
        Files.createDirectories(root.resolve("derived/interrupted/children"));
        Files.writeString(root.resolve("derived/interrupted/children/incomplete"), "not authoritative");
        try (var spool = new PendingSpool(root)) {
            spool.open();
            assertThat(spool.sourcesAfter(null, 10)).containsExactly(source);
            assertThat(spool.readyCandidates(10)).containsExactly(source);
            assertThat(Files.exists(root.resolve("derived/interrupted"))).isFalse();
        }
    }

    @Test
    void drainsSplitLeavesWithoutDuplicateCandidatesOrSourceRemoval() {
        var remaining = new ArrayList<SourceId>();
        try (var spool = new PendingSpool(root)) {
            spool.open();
            for (int i = 0; i < 140; i++) {
                SourceId source = new SourceId(new UUID(0, i));
                remaining.add(source);
                spool.publish(record(source, 1, READY));
            }
            while (!remaining.isEmpty()) {
                List<SourceId> batch = spool.readyCandidates(9);
                assertThat(batch).isNotEmpty().hasSizeLessThanOrEqualTo(9);
                for (SourceId source : batch) {
                    assertThat(remaining.remove(source)).isTrue();
                    spool.removeReady(source, 1);
                }
            }
            assertThat(spool.readyCandidates(1)).isEmpty();
            assertThat(spool.sourcesAfter(null, 200)).hasSize(140);
        }
    }

    @Test
    void traversesMoreDistinctPrioritiesThanALeafCanHold() {
        var expected = new ArrayList<SourceId>();
        try (var spool = new PendingSpool(root)) {
            spool.open();
            for (int priority = -70; priority < 70; priority++) {
                SourceId source = new SourceId(UUID.randomUUID());
                expected.addFirst(source);
                spool.publish(record(source, priority, READY));
            }
            assertThat(spool.readyCandidates(150)).containsExactlyElementsOf(expected);
        }
    }

    @Test
    void interruptedIndexUpdateRetainsCommittedSourceAndFailsClosed() throws Exception {
        SourceId source = new SourceId(UUID.randomUUID());
        try (var spool = new PendingSpool(root)) {
            spool.open();
            Files.delete(root.resolve("derived/sources"));
            Files.writeString(root.resolve("derived/sources"), "broken index");
            assertThatThrownBy(() -> spool.publish(record(source, 2, READY)))
                    .hasMessageContaining("reason:publicationFailed");
            assertThatThrownBy(() -> spool.sourcesAfter(null, 1)).hasMessageContaining("reason:notReady");
            String id = source.value().toString();
            assertThat(Files.exists(root.resolve("sources").resolve(id.substring(0, 2)).resolve(id + ".cbor"))).isTrue();
        }
        try (var spool = new PendingSpool(root)) {
            spool.open();
            assertThat(spool.sourcesAfter(null, 1)).containsExactly(source);
            assertThat(spool.readyCandidates(1)).containsExactly(source);
        }
    }

    @Test
    void missingReadyDirectoryFailsClosedInsteadOfReturningFalseAbsence() throws Exception {
        try (var spool = new PendingSpool(root)) {
            spool.open();
            spool.publish(record(new SourceId(UUID.randomUUID()), 2, READY));
            FolderPendingIndex.discard(root.resolve("derived/ready/2"));
            assertThatThrownBy(() -> spool.readyCandidates(1)).hasMessageContaining("reason:readyPageFailed");
            assertThatThrownBy(() -> spool.sourcesAfter(null, 1)).hasMessageContaining("reason:notReady");
        }
    }

    private static void assertPages(PendingSpool spool, List<SourceId> ids) {
        var actual = new ArrayList<SourceId>();
        SourceId cursor = null;
        while (true) {
            List<SourceId> page = spool.sourcesAfter(cursor, 7);
            if (page.isEmpty()) {
                break;
            }
            assertThat(page).hasSizeLessThanOrEqualTo(7);
            actual.addAll(page);
            cursor = page.getLast();
        }
        assertThat(actual).containsExactlyElementsOf(ids.stream().sorted(Comparator.comparing(SourceId::value)).toList());
    }

    private static PendingSourceRecord record(SourceId source, int priority, PendingSourceRecord.Disposition disposition) {
        var message = new StandardMessage();
        message.serial = source.value().toString();
        message.body = "not copied to index";
        message.priority = priority;
        return new PendingSourceRecord(source, disposition, PendingMessageData.capture(message));
    }
}
