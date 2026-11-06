package gr.cytech.sendium.core.storage.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import gr.cytech.sendium.core.storage.pending.PendingRecordCodec;
import gr.cytech.sendium.core.storage.pending.PendingSourceRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.OWNERSHIP_CONFLICT;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNSUPPORTED;
import static gr.cytech.sendium.core.storage.pending.PendingSourceRecord.Disposition.READY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingSpoolTest {
    @TempDir
    Path root;

    @Test
    void publishesWithoutReplacingAndValidatesAfterReopen() throws Exception {
        PendingSourceRecord original = record(UUID.randomUUID(), "first");
        try (var spool = new PendingSpool(root)) {
            spool.open();
            assertThat(spool.recoveredSources()).isZero();
            spool.publish(original);
            assertThat(spool.publish(record(original.source().value(), "replacement")).message().restore().body)
                    .isEqualTo("first");
            assertThat(Files.readAllBytes(path(original))).isEqualTo(new PendingRecordCodec().encode(original));
            try (var files = Files.list(path(original).getParent())) {
                assertThat(files.map(p -> p.getFileName().toString())).containsExactly(path(original).getFileName().toString());
            }
        }
        try (var reopened = new PendingSpool(root)) {
            reopened.open();
            assertThat(reopened.recoveredSources()).isEqualTo(1);
        }
    }

    @Test
    void excludesAnotherOwnerAndReleasesLockOnClose() {
        var first = new PendingSpool(root);
        first.open();
        try {
            var second = new PendingSpool(root);
            assertThatThrownBy(second::open).isInstanceOfSatisfying(OutboundStorageException.class,
                    failure -> assertThat(failure.reason()).isEqualTo(OWNERSHIP_CONFLICT));
        } finally {
            first.close();
            first.close();
        }
        try (var next = new PendingSpool(root)) {
            next.open();
        }
        assertThat(Files.exists(root.resolve("owner.lock"))).isTrue();
    }

    @Test
    void cleansOnlyRecognizedIncompleteFiles() throws Exception {
        initialize();
        Path bucket = Files.createDirectory(root.resolve("sources/00"));
        Files.write(root.resolve(".tmp-interrupted.part"), new byte[]{1});
        Files.write(bucket.resolve(".tmp-interrupted.part"), new byte[]{1});
        try (var spool = new PendingSpool(root)) {
            spool.open();
            assertThat(spool.recoveredSources()).isZero();
        }
        assertThat(Files.exists(root.resolve(".tmp-interrupted.part"))).isFalse();
        assertThat(Files.exists(bucket.resolve(".tmp-interrupted.part"))).isFalse();
        Files.write(bucket.resolve("unknown"), new byte[]{1});
        assertOpenFails();
        assertThat(Files.exists(bucket.resolve("unknown"))).isTrue();
    }

    @Test
    void rejectsCorruptCommittedRecordAndReleasesFailedStartupLock() throws Exception {
        PendingSourceRecord record = record(UUID.randomUUID(), "private payload");
        writeSource(record);
        Files.write(path(record), new byte[]{1});
        assertThatThrownBy(() -> new PendingSpool(root).open())
                .isInstanceOf(OutboundStorageException.class)
                .hasMessageContaining(record.source().value().toString())
                .hasMessageNotContaining("private payload");
        Files.write(path(record), new PendingRecordCodec().encode(record));
        try (var spool = new PendingSpool(root)) {
            spool.open();
        }
    }

    @Test
    void rejectsUnknownRecordVersion() throws Exception {
        PendingSourceRecord record = record(UUID.randomUUID(), "payload");
        writeSource(record);
        var mapper = new ObjectMapper(new CBORFactory());
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(Files.readAllBytes(path(record)));
        node.put("schemaVersion", 999);
        Files.write(path(record), mapper.writeValueAsBytes(node));
        assertThatThrownBy(() -> new PendingSpool(root).open()).isInstanceOfSatisfying(OutboundStorageException.class,
                failure -> assertThat(failure.reason()).isEqualTo(UNSUPPORTED));
    }

    @Test
    void rejectsIdentityMismatchAndWrongBucket() throws Exception {
        PendingSourceRecord record = record(UUID.randomUUID(), "payload");
        writeSource(record);
        Files.write(path(record), new PendingRecordCodec().encode(record(UUID.randomUUID(), "other")));
        assertOpenFails();
        Files.write(path(record), new PendingRecordCodec().encode(record));
        String wrong = path(record).getParent().getFileName().toString().equals("00") ? "01" : "00";
        Path wrongBucket = Files.createDirectories(root.resolve("sources").resolve(wrong));
        Files.move(path(record), wrongBucket.resolve(path(record).getFileName()));
        assertOpenFails();
    }

    @Test
    void rejectsMissingOrUnknownFormatWithoutDiscardingSources() throws Exception {
        PendingSourceRecord record = record(UUID.randomUUID(), "payload");
        writeSource(record);
        Files.writeString(root.resolve("format"), "unknown");
        assertOpenFails();
        Files.delete(root.resolve("format"));
        assertOpenFails();
        assertThat(Files.exists(path(record))).isTrue();
    }

    @Test
    void failedPublicationIsNotSuccessAndStopsFurtherOperations() throws Exception {
        PendingSourceRecord record = record(UUID.randomUUID(), "payload");
        try (var spool = new PendingSpool(root)) {
            spool.open();
            Files.createDirectories(path(record));
            assertThatThrownBy(() -> spool.publish(record)).isInstanceOf(OutboundStorageException.class);
            assertThatThrownBy(spool::recoveredSources).hasMessage("pendingSpoolLifecycle|error|reason:notReady");
            assertThatThrownBy(() -> spool.publish(record(UUID.randomUUID(), "another")))
                    .hasMessage("pendingSpoolLifecycle|error|reason:notReady");
            try (var entries = Files.list(path(record).getParent())) {
                assertThat(entries.map(p -> p.getFileName().toString())).noneMatch(name -> name.startsWith(".tmp-"));
            }
        }
    }

    @Test
    void cannotUseBeforeOpenOrReopenAfterClose() {
        var spool = new PendingSpool(root);
        assertThatThrownBy(spool::recoveredSources).isInstanceOf(OutboundStorageException.class);
        spool.close();
        assertThatThrownBy(spool::open).isInstanceOf(OutboundStorageException.class);
    }

    @Test
    void rejectsEmptyCommittedRecord() throws Exception {
        PendingSourceRecord record = record(UUID.randomUUID(), "payload");
        writeSource(record);
        Files.write(path(record), new byte[0]);
        assertOpenFails();
        assertThat(Files.exists(path(record))).isTrue();
    }

    @Test
    void rejectsDirectoryInPlaceOfCommittedRecordWithSourceContext() throws Exception {
        PendingSourceRecord record = record(UUID.randomUUID(), "payload");
        writeSource(record);
        Files.delete(path(record));
        Files.createDirectory(path(record));
        assertThatThrownBy(() -> new PendingSpool(root).open())
                .hasMessage("pendingSourceRead|error|sourceId:" + record.source().value() + "|reason:invalidCommittedRecord");
    }

    @ParameterizedTest
    @ValueSource(strings = {"derived", "terminal", "unknown", ".tmp-not-a-file.part"})
    void rejectsUnimplementedOrUnexpectedRootDirectories(String entry) throws Exception {
        initialize();
        Files.createDirectory(root.resolve(entry));
        assertOpenFails();
        assertThat(Files.isDirectory(root.resolve(entry))).isTrue();
    }

    @Test
    void rejectsInvalidBucketWithoutRemovingItsContents() throws Exception {
        initialize();
        Path bucket = Files.createDirectory(root.resolve("sources/zz"));
        Files.writeString(bucket.resolve("keep"), "content");
        assertOpenFails();
        assertThat(Files.readString(bucket.resolve("keep"))).isEqualTo("content");
    }

    @Test
    void rejectsFileAsSpoolRoot() throws Exception {
        Path file = Files.writeString(root.resolve("not-a-directory"), "keep");
        assertThatThrownBy(() -> new PendingSpool(file).open()).isInstanceOf(OutboundStorageException.class);
        assertThat(Files.readString(file)).isEqualTo("keep");
    }

    @Test
    void refusesFormatMarkerThatIsNotARegularFile() throws Exception {
        Files.createDirectory(root.resolve("format"));
        assertOpenFails();
    }

    private void initialize() {
        try (var spool = new PendingSpool(root)) {
            spool.open();
        }
    }

    private void writeSource(PendingSourceRecord record) {
        try (var spool = new PendingSpool(root)) {
            spool.open();
            spool.publish(record);
        }
    }

    private void assertOpenFails() {
        assertThatThrownBy(() -> new PendingSpool(root).open()).isInstanceOf(OutboundStorageException.class);
    }

    private Path path(PendingSourceRecord record) {
        String id = record.source().value().toString();
        return root.resolve("sources").resolve(id.substring(0, 2)).resolve(id + ".cbor");
    }

    private static PendingSourceRecord record(UUID id, String body) {
        var message = new StandardMessage();
        message.serial = id.toString();
        message.body = body;
        return new PendingSourceRecord(new SourceId(id), READY, PendingMessageData.capture(message));
    }
}
