package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.pending.PendingSourceRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Multipart-only disposable references; ordinary accepted sources have no disk index. */
final class FolderPendingIndex {
    private static final int LEAF_LIMIT = 128;
    private final Path root;
    private final MultipartPendingIndex multipart;

    FolderPendingIndex(Path root) throws IOException {
        this.root = root;
        multipart = new MultipartPendingIndex(root.resolve("multipart"));
        for (int priority : new int[]{3, 2, 1}) {
            readyIndex(priority);
        }
    }

    void add(PendingSourceRecord record) throws IOException {
        if (record.multipart() != null) {
            multipart.add(record.multipart());
        }
    }

    void addReady(SourceId representative, int priority) throws IOException {
        readyIndex(priority).add(sourceKey(representative));
    }

    /** Peeks a bounded batch; the selected-stage handoff must remove ready references under the owner lock. */
    List<SourceId> ready(int limit) throws IOException {
        if (limit <= 0) {
            throw new IllegalArgumentException("pendingIndexTraversal|error|reason:nonPositiveLimit");
        }
        var batch = new ArrayList<SourceId>();
        for (int priority : new int[]{3, 2, 1}) {
            if (batch.size() == limit) {
                break;
            }
            if (!Files.isDirectory(readyPath(priority), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("pendingIndexTraversal|error|reason:readyDirectoryMissing");
            }
            batch.addAll(readyIndex(priority).after(null, limit - batch.size()).stream()
                    .map(FolderPendingIndex::source).toList());
        }
        return List.copyOf(batch);
    }

    void removeReady(SourceId source, int priority) throws IOException {
        DirectoryReferenceIndex ready = readyIndex(priority);
        ready.remove(sourceKey(source));
    }

    void remove(PendingSourceRecord record) throws IOException {
        if (record.multipart() != null) {
            multipart.remove(record.multipart());
        }
    }

    private DirectoryReferenceIndex readyIndex(int priority) throws IOException {
        return new DirectoryReferenceIndex(readyPath(priority), 32, LEAF_LIMIT);
    }

    private Path readyPath(int priority) {
        return root.resolve("multipart/ready").resolve(Integer.toString(priority));
    }

    static String sourceKey(SourceId source) {
        UUID id = source.value();
        return "%016x%016x".formatted(id.getMostSignificantBits() ^ Long.MIN_VALUE,
                id.getLeastSignificantBits() ^ Long.MIN_VALUE);
    }

    static SourceId source(String key) {
        return new SourceId(new UUID(Long.parseUnsignedLong(key.substring(0, 16), 16) ^ Long.MIN_VALUE,
                Long.parseUnsignedLong(key.substring(16), 16) ^ Long.MIN_VALUE));
    }

    MultipartPendingIndex multipart() {
        return multipart;
    }

    /** Delete only disposable derived state, streaming entries and refusing symbolic-link traversal. */
    static void discard(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(path)) {
            throw new IOException("pendingIndexRecovery|error|reason:symbolicLink");
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.newDirectoryStream(path)) {
                for (Path entry : entries) {
                    discard(entry);
                }
            }
        }
        Files.delete(path);
    }
}
