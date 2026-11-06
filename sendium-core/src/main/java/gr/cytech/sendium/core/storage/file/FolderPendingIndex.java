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

/** Lightweight source and ready references; contains no message bodies or durable stage checkpoints. */
final class FolderPendingIndex {
    private static final int LEAF_LIMIT = 128;
    private final Path root;
    private final DirectoryReferenceIndex sources;
    private final DirectoryReferenceIndex priorities;
    private final MultipartPendingIndex multipart;

    FolderPendingIndex(Path root) throws IOException {
        this.root = root;
        sources = new DirectoryReferenceIndex(root.resolve("sources"), 32, LEAF_LIMIT);
        priorities = new DirectoryReferenceIndex(root.resolve("priorities"), 8, LEAF_LIMIT);
        Files.createDirectories(root.resolve("ready"));
        multipart = new MultipartPendingIndex(root.resolve("multipart"));
    }

    void add(PendingSourceRecord record) throws IOException {
        sources.add(sourceKey(record.source()));
        if (record.multipart() != null) {
            multipart.add(record.multipart());
        }
        if (record.disposition() == PendingSourceRecord.Disposition.READY) {
            addReady(record.source(), record.message().restore().priority);
        }
    }

    void addReady(SourceId representative, int priority) throws IOException {
        readyIndex(priority).add(sourceKey(representative));
        priorities.add(priorityKey(priority));
    }

    List<SourceId> sourcesAfter(SourceId after, int limit) throws IOException {
        return sources.after(after == null ? null : sourceKey(after), limit).stream()
                .map(FolderPendingIndex::source).toList();
    }

    /** Peeks a bounded batch; the selected-stage handoff must remove ready references under the owner lock. */
    List<SourceId> ready(int limit) throws IOException {
        if (limit <= 0) {
            throw new IllegalArgumentException("pendingIndexTraversal|error|reason:nonPositiveLimit");
        }
        var batch = new ArrayList<SourceId>();
        String cursor = null;
        while (batch.size() < limit) {
            List<String> page = priorities.after(cursor, 1);
            if (page.isEmpty()) {
                break;
            }
            cursor = page.getFirst();
            int priority = priority(cursor);
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
        if (ready.after(null, 1).isEmpty()) {
            priorities.remove(priorityKey(priority));
        }
    }

    void remove(PendingSourceRecord record) throws IOException {
        sources.remove(sourceKey(record.source()));
        if (record.disposition() == PendingSourceRecord.Disposition.READY) {
            removeReady(record.source(), record.message().restore().priority);
        }
    }

    private DirectoryReferenceIndex readyIndex(int priority) throws IOException {
        return new DirectoryReferenceIndex(readyPath(priority), 32, LEAF_LIMIT);
    }

    private Path readyPath(int priority) {
        return root.resolve("ready").resolve(Integer.toString(priority));
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

    private static String priorityKey(int priority) {
        // Lexically ascending references yield numerically descending signed priorities.
        return "%08x".formatted(~(priority ^ Integer.MIN_VALUE));
    }

    private static int priority(String key) {
        return ~Integer.parseUnsignedInt(key, 16) ^ Integer.MIN_VALUE;
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
