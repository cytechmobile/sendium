package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.file.PendingTerminalCodec.Intent;
import gr.cytech.sendium.core.storage.pending.MultipartKey;
import gr.cytech.sendium.core.storage.pending.MultipartMembership;
import gr.cytech.sendium.core.storage.pending.PendingPublicationCodec.Publication;
import gr.cytech.sendium.core.storage.pending.PendingRecordCodec;
import gr.cytech.sendium.core.storage.pending.PendingSourceRecord;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static gr.cytech.sendium.core.storage.OutboundStage.Role.PENDING;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.OWNERSHIP_CONFLICT;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNSUPPORTED;

/**
 * Exclusive physical pending-source files. This is not a pending store or a protocol admission boundary.
 * Callers must finish recovery/indexing before admission. No host/power-loss durability is claimed.
 */
public final class PendingSpool implements AutoCloseable {
    private static final String FORMAT = "sendium-pending-spool:2\n";
    private final Path root;
    private final PendingRecordCodec codec = new PendingRecordCodec();
    private FileChannel owner;
    private FileLock ownership;
    private boolean ready;
    private boolean closed;
    private long recoveredSources;
    private FolderPendingIndex index;
    private HeldPendingPublications publications;
    private PendingTerminalJournal terminal;
    private AcceptedSourceTraversal acceptedTraversal;

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

    synchronized boolean isReady() {
        return ready;
    }

    synchronized Optional<UUID> publicationId(SourceId source) {
        requireReady();
        try {
            return publications.bindingId(source);
        } catch (IOException failure) {
            ready = false;
            throw unavailable("pendingPublicationRead|error|sourceId:" + source.value() + "|reason:bindingReadFailed", failure);
        }
    }

    synchronized Optional<Publication> findPublication(SourceId source) {
        requireReady();
        try {
            return publications.find(source);
        } catch (IOException | OutboundStorageException failure) {
            ready = false;
            throw unavailable("pendingPublicationRead|error|sourceId:" + source.value() + "|reason:snapshotReadFailed", failure);
        }
    }

    synchronized void publishHeld(Publication publication) {
        requireReady();
        byte[] encoded = publications.encode(publication);
        try {
            publications.publish(publication, encoded);
            index.addReady(publication.representative(), publication.message().restore().priority);
        } catch (IOException | RuntimeException failure) {
            ready = false;
            throw unavailable("pendingPublication|error|sourceId:" + publication.representative().value() +
                    "|reason:bindingPublicationFailed", failure);
        }
    }

    synchronized List<String> matchingGroupsAfter(MultipartKey key, String cursor, int limit) {
        return multipartIndex(() -> index.multipart().matchingAfter(key, cursor, limit));
    }

    synchronized List<SourceId> groupSourcesAfter(UUID group, SourceId cursor, int limit) {
        return multipartIndex(() -> index.multipart().sourcesAfter(group, cursor, limit));
    }

    synchronized SourceId groupOriginal(UUID group, int ordinal) {
        return multipartIndex(() -> index.multipart().original(group, ordinal));
    }

    synchronized int groupOriginalCount(UUID group) {
        return multipartIndex(() -> index.multipart().originalCount(group));
    }

    synchronized List<UUID> dueGroups(Instant now, int limit) {
        return multipartIndex(() -> index.multipart().due(now, limit));
    }

    synchronized boolean groupScheduled(UUID group) {
        return multipartIndex(() -> index.multipart().scheduled(group));
    }

    synchronized void scheduleGroup(UUID group, Instant due) {
        multipartIndex(() -> {
            index.multipart().schedule(group, due);
            return null;
        });
    }

    synchronized void finishGroup(UUID group) {
        multipartIndex(() -> {
            index.multipart().finish(group);
            return null;
        });
    }

    synchronized void closeGroup(MultipartMembership member) {
        multipartIndex(() -> {
            index.multipart().closeGroup(member);
            return null;
        });
    }

    private <T> T multipartIndex(IndexOperation<T> operation) {
        requireReady();
        try {
            return operation.run();
        } catch (IOException | RuntimeException failure) {
            ready = false;
            if (failure instanceof OutboundStorageException storage) {
                throw storage;
            }
            throw unavailable("pendingMultipartIndex|error|reason:operationFailed", failure);
        }
    }

    public synchronized Optional<PendingSourceRecord> find(SourceId source) {
        requireReady();
        Objects.requireNonNull(source, "source");
        Path path = sourcePath(source);
        try {
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            return Optional.of(read(path, source));
        } catch (RuntimeException failure) {
            ready = false;
            throw failure;
        }
    }

    synchronized boolean isGroupClosed(UUID group) {
        requireReady();
        try {
            return terminal.isClosed(group);
        } catch (IOException | RuntimeException failure) {
            ready = false;
            throw unavailable("pendingMultipartLookup|error|groupId:" + group + "|reason:closureReadFailed", failure);
        }
    }

    /** Caller validates complete execution/ordinal ownership under the shared pending/router lock. */
    synchronized long completeSources(Set<SourceId> sources) {
        requireReady();
        var retained = new HashSet<SourceId>();
        var groups = new HashSet<UUID>();
        for (SourceId source : sources) {
            Optional<PendingSourceRecord> record = find(source);
            if (record.isPresent()) {
                retained.add(source);
                if (record.orElseThrow().multipart() != null) {
                    groups.add(record.orElseThrow().multipart().groupId());
                }
            }
        }
        if (retained.isEmpty()) {
            return 0;
        }
        Intent intent = new Intent(UUID.randomUUID(), sources, groups);
        try {
            terminal.publish(intent);
            for (UUID group : groups) {
                terminal.closeGroup(group);
            }
            var publicationsRemoved = new HashSet<UUID>();
            for (SourceId source : retained) {
                Optional<Publication> binding = findPublication(source);
                if (binding.isPresent() && publicationsRemoved.add(binding.orElseThrow().id())) {
                    Publication publication = binding.orElseThrow();
                    index.removeReady(publication.representative(), publication.message().restore().priority);
                    publications.remove(publication);
                }
            }
            for (SourceId source : retained) {
                PendingSourceRecord record = read(sourcePath(source), source);
                Files.delete(sourcePath(source));
                index.remove(record);
            }
            for (UUID group : groups) {
                if (index.multipart().sourcesAfter(group, null, 1).isEmpty()) {
                    terminal.forgetGroup(group);
                }
            }
            terminal.finish(intent.id());
            return retained.size();
        } catch (IOException | RuntimeException failure) {
            ready = false;
            throw unavailable("pendingCompletion|error|intentId:" + intent.id() + "|reason:sourceRemovalFailed", failure);
        }
    }

    public synchronized List<SourceId> sourcesAfter(SourceId afterExclusive, int limit) {
        requireReady();
        if (limit <= 0) {
            throw new IllegalArgumentException("pendingSourcePage|error|reason:nonPositiveLimit");
        }
        try {
            // Administrative ordered paging: bounded IDs, no duplicate disk index or payload reads.
            Comparator<SourceId> order = Comparator.comparing(SourceId::value);
            var page = new PriorityQueue<SourceId>(Math.min(limit, 128), order.reversed());
            visitAccepted(path -> {
                SourceId source = sourceName(path.getFileName().toString());
                if (afterExclusive == null || order.compare(source, afterExclusive) > 0) {
                    if (page.size() < limit) {
                        page.add(source);
                    } else if (order.compare(source, page.element()) < 0) {
                        page.remove();
                        page.add(source);
                    }
                }
            });
            return page.stream().sorted(order).toList();
        } catch (IOException | RuntimeException failure) {
            ready = false;
            if (failure instanceof OutboundStorageException storage) {
                throw storage;
            }
            throw unavailable("pendingIndexTraversal|error|reason:sourcePageFailed", failure);
        }
    }

    synchronized void forEachRecord(Consumer<PendingSourceRecord> action) {
        requireReady();
        try {
            visitAccepted(path -> action.accept(read(path, sourceName(path.getFileName().toString()))));
        } catch (IOException | RuntimeException failure) {
            ready = false;
            if (failure instanceof OutboundStorageException storage) {
                throw storage;
            }
            throw unavailable("pendingRecovery|error|reason:sourceTraversalFailed", failure);
        }
    }

    synchronized AcceptedSourceTraversal traversal() {
        requireReady();
        if (acceptedTraversal == null) {
            acceptedTraversal = new AcceptedSourceTraversal(root.resolve("accepted"));
        }
        return acceptedTraversal;
    }

    synchronized Optional<PendingSourceRecord> findCandidate(AcceptedSourceTraversal.Candidate candidate) {
        requireReady();
        Path path = acceptedPath(candidate.priority(), candidate.source());
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            return Optional.of(read(path, candidate.source()));
        } catch (RuntimeException failure) {
            ready = false;
            throw failure;
        }
    }

    // Prepared multipart work only; callers must retain the owner lock.
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
        Optional<PendingSourceRecord> retained = find(record.source());
        if (retained.isPresent()) {
            return retained.orElseThrow();
        }
        Path target = acceptedPath(requirePriority(record.message().priority()), record.source());
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
        // Reject an old format before cleaning or rebuilding any of its data.
        Path format = root.resolve("format");
        if (Files.exists(root.resolve("sources"), LinkOption.NOFOLLOW_LINKS)) {
            throw new OutboundStorageException(PENDING, UNSUPPORTED, "pendingRecovery|error|reason:unsupportedSpoolFormat");
        }
        if (Files.exists(format, LinkOption.NOFOLLOW_LINKS)) {
            requireFile(format);
            if (Files.size(format) != FORMAT.length() || !FORMAT.equals(Files.readString(format))) {
                throw new OutboundStorageException(PENDING, UNSUPPORTED, "pendingRecovery|error|reason:unsupportedSpoolFormat");
            }
        }
        try (var entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (temporary(name)) {
                    deleteTemporary(entry);
                } else if (name.equals("sources")) {
                    throw new OutboundStorageException(PENDING, UNSUPPORTED, "pendingRecovery|error|reason:unsupportedSpoolFormat");
                } else if (!name.equals("owner.lock") && !name.equals("format") && !name.equals("accepted") &&
                        !name.equals("derived") && !name.equals("terminal") && !name.equals("closed-groups")) {
                    throw unavailable("pendingRecovery|error|reason:unexpectedSpoolEntry", null);
                }
            }
        }
        if (!Files.exists(format, LinkOption.NOFOLLOW_LINKS)) {
            // A crash before initial format publication cannot have admitted source files.
            if (Files.exists(root.resolve("accepted"), LinkOption.NOFOLLOW_LINKS) ||
                    Files.exists(root.resolve("terminal"), LinkOption.NOFOLLOW_LINKS) ||
                    Files.exists(root.resolve("closed-groups"), LinkOption.NOFOLLOW_LINKS)) {
                throw unavailable("pendingRecovery|error|reason:missingFormatMarker", null);
            }
            publishBytes(format, FORMAT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        requireFile(format);
        if (Files.size(format) != FORMAT.length() || !FORMAT.equals(Files.readString(format))) {
            throw new OutboundStorageException(PENDING, UNSUPPORTED, "pendingRecovery|error|reason:unsupportedSpoolFormat");
        }
        Path accepted = root.resolve("accepted");
        Files.createDirectories(accepted);
        requireDirectory(accepted);
        for (int priority : new int[]{3, 2, 1}) {
            Path directory = accepted.resolve(Integer.toString(priority));
            Files.createDirectories(directory);
            requireDirectory(directory);
        }
        // Exercise the actual primitive even when opening an empty existing spool.
        Path probe = root.resolve(".tmp-" + UUID.randomUUID());
        publishBytes(probe, new byte[0]);
        Files.delete(probe);
        terminal = new PendingTerminalJournal(root);
        terminal.validate();
        // Validate committed intent/source envelopes first; replay precedes cross-record group checks.
        terminal.forEachIntent(this::validateIntentSources);
        // Directory enumeration is weakly consistent while entries are removed: confirm it is empty.
        while (terminal.hasIntents()) {
            terminal.forEachIntent(intent -> {
                validateIntentSources(intent);
                for (UUID group : intent.closedGroups()) {
                    terminal.closeGroup(group);
                }
                for (SourceId source : intent.sources()) {
                    Path path = sourcePath(source);
                    if (!Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                        requireFile(path);
                        Files.delete(path);
                    }
                }
                terminal.finish(intent.id());
            });
        }
        FolderPendingIndex.discard(root.resolve("derived"));
        index = new FolderPendingIndex(root.resolve("derived"));
        publications = new HeldPendingPublications(root.resolve("derived/publications"));
        visitAccepted(path -> {
            SourceId source = sourceName(path.getFileName().toString());
            PendingSourceRecord record = read(path, source);
            if (!sourcePath(source).equals(path)) {
                throw unavailable("pendingRecovery|error|sourceId:" + source.value() + "|reason:misplacedSource", null);
            }
            try {
                index.add(record);
                if (record.multipart() != null && terminal.isClosed(record.multipart().groupId())) {
                    index.multipart().closeGroup(record.multipart());
                }
            } catch (IOException failure) {
                throw unavailable("pendingRecovery|error|sourceId:" + source.value() + "|reason:indexRebuildFailed", failure);
            }
            recoveredSources++;
        });
        terminal.forEachClosedGroup(group -> {
            if (index.multipart().sourcesAfter(group, null, 1).isEmpty()) {
                terminal.forgetGroup(group);
            }
        });
    }

    private void visitAccepted(Consumer<Path> action) throws IOException {
        for (int priority : new int[]{3, 2, 1}) {
            requireDirectory(root.resolve("accepted").resolve(Integer.toString(priority)));
        }
        try (var priorities = Files.newDirectoryStream(root.resolve("accepted"))) {
            for (Path priority : priorities) {
                if (!priority.getFileName().toString().matches("[123]")) {
                    throw unavailable("pendingRecovery|error|reason:invalidPriorityDirectory", null);
                }
                requireDirectory(priority);
                try (var buckets = Files.newDirectoryStream(priority)) {
                    for (Path bucket : buckets) {
                        if (!bucket.getFileName().toString().matches("[0-9a-f]{2}")) {
                            throw unavailable("pendingRecovery|error|reason:invalidSourceBucket", null);
                        }
                        requireDirectory(bucket);
                        try (var records = Files.newDirectoryStream(bucket)) {
                            for (Path path : records) {
                                String name = path.getFileName().toString();
                                if (temporary(name)) {
                                    if (ready) {
                                        throw unavailable("pendingSourcePage|error|reason:unexpectedTemporaryEntry", null);
                                    }
                                    deleteTemporary(path);
                                    continue;
                                }
                                SourceId source = sourceName(name);
                                if (!source.value().toString().startsWith(bucket.getFileName().toString())) {
                                    throw unavailable("pendingRecovery|error|sourceId:" + source.value() + "|reason:misplacedSource", null);
                                }
                                try {
                                    requireFile(path);
                                } catch (OutboundStorageException failure) {
                                    throw unavailable("pendingSourceRead|error|sourceId:" + source.value() + "|reason:invalidCommittedRecord", failure);
                                }
                                action.accept(path);
                            }
                        }
                    }
                }
            }
        }
    }

    private void validateIntentSources(Intent intent) {
        for (SourceId source : intent.sources()) {
            Path path = sourcePath(source);
            if (!Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                PendingSourceRecord record = read(path, source);
                if (record.multipart() != null && !intent.closedGroups().contains(record.multipart().groupId())) {
                    throw unavailable("pendingTerminalRecovery|error|intentId:" + intent.id() + "|reason:missingGroupClosure", null);
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
            if (!acceptedPath(requirePriority(record.message().priority()), expected).equals(path)) {
                throw unavailable("pendingSourceRead|error|sourceId:" + expected.value() + "|reason:misplacedSource", null);
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
        Path found = null;
        for (int priority : new int[]{3, 2, 1}) {
            Path path = acceptedPath(priority, source);
            try {
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException missing) {
                continue;
            } catch (IOException failure) {
                ready = false;
                throw unavailable("pendingSourceRead|error|sourceId:" + source.value() + "|reason:lookupFailed", failure);
            }
            if (found != null) {
                ready = false;
                throw unavailable("pendingSourceRead|error|sourceId:" + source.value() + "|reason:duplicateSource", null);
            }
            found = path;
        }
        return found == null ? acceptedPath(2, source) : found;
    }

    private Path acceptedPath(int priority, SourceId source) {
        String id = source.value().toString();
        return root.resolve("accepted").resolve(Integer.toString(priority)).resolve(id.substring(0, 2)).resolve(id + ".cbor");
    }

    static int requirePriority(int priority) {
        if (priority < 1 || priority > 3) {
            throw new OutboundStorageException(PENDING, UNSUPPORTED, "pendingAdmission|error|reason:unsupportedPriority");
        }
        return priority;
    }

    private static SourceId sourceName(String name) {
        if (!name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.cbor")) {
            throw unavailable("pendingRecovery|error|reason:invalidSourceFilename", null);
        }
        return new SourceId(UUID.fromString(name.substring(0, 36)));
    }

    static boolean publishBytes(Path target, byte[] bytes) throws IOException {
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

    static boolean temporary(String name) {
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
            if (acceptedTraversal != null) {
                acceptedTraversal.close();
            }
        } catch (IOException failure) {
            releaseFailure = failure;
        }
        try {
            if (ownership != null) {
                ownership.release();
            }
        } catch (IOException failure) {
            if (releaseFailure == null) {
                releaseFailure = failure;
            } else {
                releaseFailure.addSuppressed(failure);
            }
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

    @FunctionalInterface
    private interface IndexOperation<T> {
        T run() throws IOException;
    }
}
