package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import gr.cytech.sendium.core.storage.pending.PendingSourceRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static gr.cytech.sendium.core.storage.pending.PendingSourceRecord.Disposition.HELD;
import static gr.cytech.sendium.core.storage.pending.PendingSourceRecord.Disposition.READY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingSpoolIndexTest {
    @TempDir Path root;

    @Test
    void pagesInJavaUuidOrderAcrossPrioritiesIncludingBothSignedLongBoundaries() {
        var ids = new ArrayList<SourceId>();
        var random = new Random(339);
        for (int i = 0; i < 140; i++) {
            ids.add(new SourceId(new UUID(random.nextLong(), random.nextLong())));
        }
        for (long most : new long[]{Long.MIN_VALUE, -1, 0, Long.MAX_VALUE}) {
            for (long least : new long[]{Long.MIN_VALUE, -1, 0, Long.MAX_VALUE}) {
                ids.add(new SourceId(new UUID(most, least)));
            }
        }
        try (var spool = new PendingSpool(root)) {
            spool.open();
            for (int i = 0; i < ids.size(); i++) {
                spool.publish(record(ids.get(i), i % 3 + 1, READY));
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
    void directTraversalStreamsLargeBucketWithoutOrdinaryDiskReferences() throws Exception {
        var expected = new HashSet<SourceId>();
        try (var spool = new PendingSpool(root)) {
            spool.open();
            for (int i = 0; i < 140; i++) {
                SourceId source = new SourceId(new UUID(0, i));
                expected.add(source);
                spool.publish(record(source, 2, READY));
            }
            var active = new HashSet<SourceId>();
            boolean complete = false;
            while (!complete) {
                var page = spool.traversal().pollPriority(2, 9, 32, source -> !active.contains(source));
                for (var candidate : page.candidates()) {
                    assertThat(active.add(candidate.source())).isTrue();
                }
                complete = page.roundComplete();
            }
            assertThat(active).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(spool.sourcesAfter(null, 200)).hasSize(140);
            assertThat(spool.readyCandidates(1)).isEmpty(); // Reserved for prepared multipart work only.
            assertThat(root.resolve("derived/sources")).doesNotExist();
            assertThat(root.resolve("derived/priorities")).doesNotExist();
            assertThat(root.resolve("derived/ready")).doesNotExist();
            try (var files = Files.walk(root.resolve("derived"))) {
                assertThat(files.filter(Files::isRegularFile).toList()).isEmpty();
            }
        }
    }

    @Test
    void candidateTraversalAndAdministrativePagingDoNotReadPayloads() throws Exception {
        SourceId source = new SourceId(UUID.randomUUID());
        try (var spool = new PendingSpool(root)) {
            spool.open();
            spool.publish(record(source, 1, READY));
            Files.write(path(1, source), new byte[0]);
            assertThat(spool.sourcesAfter(null, 10)).containsExactly(source);
            assertThat(spool.traversal().pollPriority(1, 1, 1024, candidate -> true).candidates())
                    .extracting(AcceptedSourceTraversal.Candidate::source).containsExactly(source);
        }
        assertThatThrownBy(() -> new PendingSpool(root).open()).hasMessageContaining("invalidCommittedRecord");
    }

    @Test
    void rebuildsDiscardedDerivedStateWithoutLosingAcceptedSources() throws Exception {
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
            assertThat(spool.find(source)).isPresent();
            assertThat(root.resolve("derived/interrupted")).doesNotExist();
        }
    }

    @Test
    void lookupAndIdempotentAdmissionFindSourcesAcrossAllThreePriorities() {
        try (var spool = new PendingSpool(root)) {
            spool.open();
            for (int priority : new int[]{3, 2, 1}) {
                SourceId source = new SourceId(UUID.randomUUID());
                spool.publish(record(source, priority, HELD));
                assertThat(spool.find(source).orElseThrow().message().priority()).isEqualTo(priority);
                assertThat(spool.publish(record(source, priority % 3 + 1, READY)).disposition()).isEqualTo(HELD);
            }
            assertThat(spool.sourcesAfter(null, 10)).hasSize(3);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 4, Integer.MAX_VALUE})
    void unsupportedPriorityDoesNotCommitOrInvalidateSpool(int priority) {
        SourceId source = new SourceId(UUID.randomUUID());
        try (var spool = new PendingSpool(root)) {
            spool.open();
            assertThatThrownBy(() -> spool.publish(record(source, priority, READY))).hasMessageContaining("unsupportedPriority");
            assertThat(spool.find(source)).isEmpty();
            spool.publish(record(source, 2, READY));
            assertThat(spool.find(source)).isPresent();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void oldFormatIsRejectedBeforeCleaningOrRebuildingItsFiles(boolean marker) throws Exception {
        if (marker) {
            Files.writeString(root.resolve("format"), "sendium-pending-spool:1\n");
        }
        Files.createDirectories(root.resolve("sources/00"));
        Path source = root.resolve("sources/00/00000000-0000-0000-0000-000000000001.cbor");
        Files.writeString(source, "old accepted payload");
        Path temporary = root.resolve(".tmp-interrupted.part");
        Files.writeString(temporary, "old temporary payload");
        Files.createDirectory(root.resolve("derived"));
        Files.writeString(root.resolve("derived/retained"), "old reference");
        assertThatThrownBy(() -> new PendingSpool(root).open()).hasMessageContaining("unsupportedSpoolFormat");
        if (marker) {
            assertThat(Files.readString(root.resolve("format"))).isEqualTo("sendium-pending-spool:1\n");
        } else {
            assertThat(root.resolve("format")).doesNotExist();
        }
        assertThat(Files.readString(source)).isEqualTo("old accepted payload");
        assertThat(Files.readString(temporary)).isEqualTo("old temporary payload");
        assertThat(Files.readString(root.resolve("derived/retained"))).isEqualTo("old reference");
        assertThat(root.resolve("accepted")).doesNotExist();
    }

    @Test
    void duplicateUuidAcrossPrioritiesFailsLookupAndRecoveryWithoutRemovingEitherFile() throws Exception {
        SourceId source = new SourceId(UUID.randomUUID());
        try (var spool = new PendingSpool(root)) {
            spool.open();
            spool.publish(record(source, 2, READY));
            Files.createDirectories(path(3, source).getParent());
            Files.copy(path(2, source), path(3, source));
            assertThatThrownBy(() -> spool.find(source)).hasMessageContaining("duplicateSource");
            assertThat(spool.isReady()).isFalse();
        }
        assertThatThrownBy(() -> new PendingSpool(root).open()).isInstanceOf(gr.cytech.sendium.core.storage.OutboundStorageException.class);
        assertThat(path(2, source)).isRegularFile();
        assertThat(path(3, source)).isRegularFile();
    }

    @Test
    void missingPriorityAndMalformedEntriesInvalidateAdministrativePaging() throws Exception {
        try (var spool = new PendingSpool(root)) {
            spool.open();
            Files.delete(root.resolve("accepted/3"));
            assertThatThrownBy(() -> spool.sourcesAfter(null, 1)).isInstanceOf(gr.cytech.sendium.core.storage.OutboundStorageException.class);
            assertThat(spool.isReady()).isFalse();
        }
        try (var spool = new PendingSpool(root)) {
            spool.open();
            Files.createDirectory(root.resolve("accepted/2/zz"));
            assertThatThrownBy(() -> spool.sourcesAfter(null, 1)).hasMessageContaining("invalidSourceBucket");
            assertThat(spool.isReady()).isFalse();
        }
    }

    @Test
    void missingPreparedReadyDirectoryFailsClosed() throws Exception {
        try (var spool = new PendingSpool(root)) {
            spool.open();
            FolderPendingIndex.discard(root.resolve("derived/multipart/ready/2"));
            assertThatThrownBy(() -> spool.readyCandidates(1)).hasMessageContaining("reason:readyPageFailed");
            assertThatThrownBy(() -> spool.sourcesAfter(null, 1)).hasMessageContaining("reason:notReady");
        }
    }

    private Path path(int priority, SourceId source) {
        String id = source.value().toString();
        return root.resolve("accepted").resolve(Integer.toString(priority)).resolve(id.substring(0, 2)).resolve(id + ".cbor");
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
