package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.outbound.OutboundWork.WorkId;
import gr.cytech.sendium.core.storage.file.RoutedRecordCodec.StoredWork;
import gr.cytech.sendium.core.storage.pending.PendingSourceRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Disposable multipart and selected-stage references; ordinary pending sources have no disk index. */
final class FolderPendingIndex {
    private static final int LEAF_LIMIT = 128;
    private final Path root;
    private final MultipartPendingIndex multipart;
    private final DirectoryReferenceIndex selectedReady;
    private final DirectoryReferenceIndex routedTerminal;

    FolderPendingIndex(Path root) throws IOException {
        this.root = root;
        multipart = new MultipartPendingIndex(root.resolve("multipart"));
        selectedReady = new DirectoryReferenceIndex(root.resolve("selected/ready"), 32, LEAF_LIMIT);
        routedTerminal = new DirectoryReferenceIndex(root.resolve("routed/terminal"), 32, LEAF_LIMIT);
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

    void addSelected(SelectionId selection, java.util.Set<SourceId> sources) throws IOException {
        for (SourceId source : sources) {
            Path target = selectedOwnerPath(source);
            Files.createDirectories(target.getParent());
            Optional<SelectionId> existing = selectedOwner(source);
            if (existing.isPresent() && !existing.orElseThrow().equals(selection)) {
                throw new IOException("selectedRecovery|error|reason:conflictingSourceOwner");
            }
            Files.writeString(target, selection.value().toString());
        }
        selectedReady.add(sourceKey(new SourceId(selection.value())));
    }

    Optional<SelectionId> selectedOwner(SourceId source) throws IOException {
        Path path = selectedOwnerPath(source);
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("selectedLookup|error|reason:invalidReference");
        }
        return Optional.of(new SelectionId(UUID.fromString(Files.readString(path))));
    }

    List<SelectionId> selectedReady(int limit) throws IOException {
        return selectedReady.after(null, limit).stream().map(key -> new SelectionId(source(key).value())).toList();
    }

    void unscheduleSelected(SelectionId id) throws IOException {
        selectedReady.remove(sourceKey(new SourceId(id.value())));
    }

    boolean selectedScheduled(SelectionId id) throws IOException {
        String key = sourceKey(new SourceId(id.value()));
        var value = new java.math.BigInteger(key, 16);
        String before = value.signum() == 0 ? null : "%032x".formatted(value.subtract(java.math.BigInteger.ONE));
        return selectedReady.after(before, 1).contains(key);
    }

    void removeSelected(SelectionId id, java.util.Set<SourceId> sources) throws IOException {
        unscheduleSelected(id);
        for (SourceId source : sources) {
            Files.deleteIfExists(selectedOwnerPath(source));
        }
    }

    private Path selectedOwnerPath(SourceId source) {
        String id = source.value().toString();
        return root.resolve("selected/owners").resolve(id.substring(0, 2)).resolve(id);
    }

    void addRouted(StoredWork record, boolean terminal) throws IOException {
        var work = record.work();
        Path selection = routedSelectionPath(work.selection());
        Files.createDirectories(selection.getParent());
        if (routedSelection(work.selection()).filter(id -> !id.equals(work.id())).isPresent()) {
            throw new IOException("routedRecovery|error|reason:conflictingSelectionOwner");
        }
        Files.writeString(selection, work.id().value().toString());
        for (SourceId source : record.sources()) {
            Path path = routedOwnerPath(source);
            Files.createDirectories(path.getParent());
            if (routedOwner(source).filter(owner -> !owner.work().equals(work.id())).isPresent()) {
                throw new IOException("routedRecovery|error|reason:conflictingSourceOwner");
            }
            Files.writeString(path, work.selection().value() + "\n" + work.id().value());
        }
        if (terminal) {
            routedTerminal.add(sourceKey(new SourceId(work.id().value())));
        } else {
            routedReadyIndex(work.destination()).add(sourceKey(new SourceId(work.id().value())));
        }
    }

    Optional<WorkId> routedSelection(SelectionId id) throws IOException {
        Path path = routedSelectionPath(id);
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        requireReference(path);
        return Optional.of(new WorkId(UUID.fromString(Files.readString(path))));
    }

    Optional<RoutedOwner> routedOwner(SourceId id) throws IOException {
        Path path = routedOwnerPath(id);
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        requireReference(path);
        String[] values = Files.readString(path).split("\n", -1);
        if (values.length != 2) {
            throw new IOException("routedLookup|error|reason:invalidOwnerReference");
        }
        return Optional.of(new RoutedOwner(new SelectionId(UUID.fromString(values[0])), new WorkId(UUID.fromString(values[1]))));
    }

    List<WorkId> routedReady(String destination, int limit) throws IOException {
        return routedReadyAfter(destination, null, limit);
    }

    List<WorkId> routedReadyAfter(String destination, WorkId after, int limit) throws IOException {
        return routedReadyIndex(destination).after(after == null ? null : sourceKey(new SourceId(after.value())), limit)
                .stream().map(key -> new WorkId(source(key).value())).toList();
    }

    void scheduleRouted(StoredWork record) throws IOException {
        routedReadyIndex(record.work().destination()).add(sourceKey(new SourceId(record.work().id().value())));
    }

    List<WorkId> terminalRoutedAfter(WorkId after, int limit) throws IOException {
        return routedTerminal.after(after == null ? null : sourceKey(new SourceId(after.value())), limit).stream()
                .map(key -> new WorkId(source(key).value())).toList();
    }

    void unscheduleRouted(StoredWork record) throws IOException {
        routedReadyIndex(record.work().destination()).remove(sourceKey(new SourceId(record.work().id().value())));
    }

    void markRoutedTerminal(StoredWork record) throws IOException {
        unscheduleRouted(record);
        routedTerminal.add(sourceKey(new SourceId(record.work().id().value())));
    }

    void removeRouted(StoredWork record) throws IOException {
        unscheduleRouted(record);
        routedTerminal.remove(sourceKey(new SourceId(record.work().id().value())));
        Files.deleteIfExists(routedSelectionPath(record.work().selection()));
        for (SourceId source : record.sources()) {
            Files.deleteIfExists(routedOwnerPath(source));
        }
    }

    private DirectoryReferenceIndex routedReadyIndex(String destination) throws IOException {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(destination.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return new DirectoryReferenceIndex(root.resolve("routed/destinations").resolve(hash).resolve("ready"), 32, LEAF_LIMIT);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("routedIndex|error|reason:sha256Unavailable", failure);
        }
    }

    private Path routedSelectionPath(SelectionId id) {
        String name = id.value().toString();
        return root.resolve("routed/selections").resolve(name.substring(0, 2)).resolve(name);
    }

    private Path routedOwnerPath(SourceId id) {
        String name = id.value().toString();
        return root.resolve("routed/owners").resolve(name.substring(0, 2)).resolve(name);
    }

    private static void requireReference(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("routedLookup|error|reason:invalidReference");
        }
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

    record RoutedOwner(SelectionId selection, WorkId work) {}
}
