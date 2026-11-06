package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.pending.PendingRecordCodec;
import gr.cytech.sendium.core.storage.pending.PendingSourceRecord;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static gr.cytech.sendium.core.storage.OutboundStage.Role.PENDING;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.OWNERSHIP_CONFLICT;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNSUPPORTED;

/**
 * Exclusive physical pending-source files. This is not a pending store or a protocol admission boundary.
 * Callers must finish recovery/indexing before admission. No host/power-loss durability is claimed.
 */
public final class PendingSpool implements AutoCloseable {
    private static final String FORMAT = "sendium-pending-spool:1\n";
    private final Path root;
    private final PendingRecordCodec codec = new PendingRecordCodec();
    private FileChannel owner;
    private FileLock ownership;
    private boolean ready;
    private boolean closed;
    private long recoveredSources;
    private FolderPendingIndex index;

    public PendingSpool(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public synchronized void open() {
        if (closed) {
            throw unavailable("pendingSpoolLifecycle|error|reason:closed", null);
        }
        if (ready) {
            return;
        }
        try {
            Files.createDirectories(root);
            requireDirectory(root);
            Path lockPath = root.resolve("owner.lock");
            if (Files.isSymbolicLink(lockPath)) {
                throw unavailable("pendingSpoolOwnership|error|reason:symbolicLinkLock", null);
            }
            owner = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                ownership = owner.tryLock();
            } catch (OverlappingFileLockException failure) {
                throw conflict();
            }
            if (ownership == null) {
                throw conflict();
            }
            validateLayout();
            ready = true;
        } catch (IOException | RuntimeException failure) {
            try {
                close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            if (failure instanceof OutboundStorageException storage) {
                throw storage;
            }
            throw unavailable("pendingRecovery|error|reason:spoolOpenFailed", failure);
        }
    }

    public synchronized long recoveredSources() {
        requireReady();
        return recoveredSources;
    }

    public synchronized List<SourceId> sourcesAfter(SourceId afterExclusive, int limit) {
        requireReady();
        try {
            return index.sourcesAfter(afterExclusive, limit);
        } catch (IOException failure) {
            ready = false;
            throw unavailable("pendingIndexTraversal|error|reason:sourcePageFailed", failure);
        }
    }

    // Selection integration follows in the pending-store task; callers must retain the owner lock.
    synchronized List<SourceId> readyCandidates(int limit) {
        requireReady();
        try {
            return index.ready(limit);
        } catch (IOException failure) {
            ready = false;
            throw unavailable("pendingIndexTraversal|error|reason:readyPageFailed", failure);
        }
    }

    synchronized void removeReady(SourceId source, int priority) {
        requireReady();
        try {
            index.removeReady(source, priority);
        } catch (IOException failure) {
            ready = false;
            throw unavailable("pendingIndexTransition|error|sourceId:" + source.value() + "|reason:readyRemovalFailed", failure);
        }
    }

    /** Returns the first accepted record for this UUID, never replacing an existing source. */
    public synchronized PendingSourceRecord publish(PendingSourceRecord record) {
        requireReady();
        byte[] encoded = codec.encode(Objects.requireNonNull(record, "record"));
        Path target = sourcePath(record.source());
        try {
            Files.createDirectories(target.getParent());
            requireDirectory(target.getParent());
            if (publishBytes(target, encoded)) {
                index.add(record);
                return record;
            }
            return read(target, record.source());
        } catch (IOException | RuntimeException failure) {
            ready = false;
            if (failure instanceof OutboundStorageException storage) {
                throw storage;
            }
            throw unavailable("pendingAdmission|error|sourceId:" + record.source().value() + "|reason:publicationFailed", failure);
        }
    }

    private void validateLayout() throws IOException {
        try (var entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (temporary(name)) {
                    deleteTemporary(entry);
                } else if (!name.equals("owner.lock") && !name.equals("format") && !name.equals("sources") &&
                        !name.equals("derived")) {
                    throw unavailable("pendingRecovery|error|reason:unexpectedSpoolEntry", null);
                }
            }
        }
        Path format = root.resolve("format");
        if (!Files.exists(format, LinkOption.NOFOLLOW_LINKS)) {
            // A crash before initial format publication cannot have admitted source files.
            if (Files.exists(root.resolve("sources"), LinkOption.NOFOLLOW_LINKS)) {
                throw unavailable("pendingRecovery|error|reason:missingFormatMarker", null);
            }
            publishBytes(format, FORMAT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        requireFile(format);
        if (Files.size(format) != FORMAT.length() || !FORMAT.equals(Files.readString(format))) {
            throw new OutboundStorageException(PENDING, UNSUPPORTED, "pendingRecovery|error|reason:unsupportedSpoolFormat");
        }
        Path sources = root.resolve("sources");
        Files.createDirectories(sources);
        requireDirectory(sources);
        // Exercise the actual primitive even when opening an empty existing spool.
        Path probe = root.resolve(".tmp-" + UUID.randomUUID());
        publishBytes(probe, new byte[0]);
        Files.delete(probe);
        FolderPendingIndex.discard(root.resolve("derived"));
        index = new FolderPendingIndex(root.resolve("derived"));
        try (var buckets = Files.newDirectoryStream(sources)) {
            for (Path bucket : buckets) {
                if (!bucket.getFileName().toString().matches("[0-9a-f]{2}")) {
                    throw unavailable("pendingRecovery|error|reason:invalidSourceBucket", null);
                }
                requireDirectory(bucket);
                try (var records = Files.newDirectoryStream(bucket)) {
                    for (Path path : records) {
                        String name = path.getFileName().toString();
                        if (temporary(name)) {
                            deleteTemporary(path);
                            continue;
                        }
                        SourceId source = sourceName(name);
                        if (!sourcePath(source).equals(path)) {
                            throw unavailable("pendingRecovery|error|sourceId:" + source.value() + "|reason:misplacedSource", null);
                        }
                        index.add(read(path, source));
                        recoveredSources++;
                    }
                }
            }
        }
    }

    private PendingSourceRecord read(Path path, SourceId expected) {
        try {
            requireFile(path);
            PendingSourceRecord record = codec.decode(Files.readAllBytes(path));
            if (!record.source().equals(expected)) {
                throw unavailable("pendingSourceRead|error|sourceId:" + expected.value() + "|reason:identityMismatch", null);
            }
            return record;
        } catch (OutboundStorageException failure) {
            throw new OutboundStorageException(PENDING, failure.reason(),
                    "pendingSourceRead|error|sourceId:" + expected.value() + "|reason:invalidCommittedRecord", failure);
        } catch (IOException failure) {
            throw unavailable("pendingSourceRead|error|sourceId:" + expected.value() + "|reason:readFailed", failure);
        }
    }

    private Path sourcePath(SourceId source) {
        String id = source.value().toString();
        return root.resolve("sources").resolve(id.substring(0, 2)).resolve(id + ".cbor");
    }

    private static SourceId sourceName(String name) {
        if (!name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.cbor")) {
            throw unavailable("pendingRecovery|error|reason:invalidSourceFilename", null);
        }
        return new SourceId(UUID.fromString(name.substring(0, 36)));
    }

    private static boolean publishBytes(Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".tmp-", ".part");
        Throwable publicationFailure = null;
        try {
            Files.write(temporary, bytes);
            try {
                Files.createLink(target, temporary);
                return true;
            } catch (FileAlreadyExistsException existing) {
                return false;
            } catch (UnsupportedOperationException unsupported) {
                throw new OutboundStorageException(PENDING, UNSUPPORTED,
                        "pendingSpoolPublication|error|reason:hardLinksUnsupported", unsupported);
            }
        } catch (IOException | RuntimeException failure) {
            publicationFailure = failure;
            throw failure;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException | RuntimeException cleanup) {
                if (publicationFailure == null) {
                    throw cleanup;
                }
                publicationFailure.addSuppressed(cleanup);
            }
        }
    }

    private static boolean temporary(String name) {
        return name.matches("\\.tmp-[a-zA-Z0-9-]+(?:\\.part)?");
    }

    private static void deleteTemporary(Path path) throws IOException {
        requireFile(path);
        Files.delete(path);
    }

    private static void requireDirectory(Path path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw unavailable("pendingSpoolValidation|error|reason:realDirectoryRequired", null);
        }
    }

    private static void requireFile(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw unavailable("pendingSpoolValidation|error|reason:regularFileRequired", null);
        }
    }

    private void requireReady() {
        if (!ready) {
            throw unavailable("pendingSpoolLifecycle|error|reason:notReady", null);
        }
    }

    private static OutboundStorageException conflict() {
        return new OutboundStorageException(PENDING, OWNERSHIP_CONFLICT, "pendingSpoolOwnership|error|reason:alreadyOwned");
    }

    private static OutboundStorageException unavailable(String message, Throwable cause) {
        return new OutboundStorageException(PENDING, UNAVAILABLE, message, cause);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        ready = false;
        closed = true;
        IOException releaseFailure = null;
        try {
            if (ownership != null) {
                ownership.release();
            }
        } catch (IOException failure) {
            releaseFailure = failure;
        } finally {
            if (owner != null) {
                try {
                    owner.close();
                } catch (IOException failure) {
                    if (releaseFailure == null) {
                        releaseFailure = failure;
                    } else {
                        releaseFailure.addSuppressed(failure);
                    }
                }
            }
        }
        if (releaseFailure != null) {
            throw unavailable("pendingSpoolOwnership|error|reason:lockCloseFailed", releaseFailure);
        }
    }
}
