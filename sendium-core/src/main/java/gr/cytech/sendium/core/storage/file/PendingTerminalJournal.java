package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.file.PendingTerminalCodec.Intent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;

import static gr.cytech.sendium.core.storage.OutboundStage.Role.PENDING;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;

/** Committed terminal intents plus closure markers; guarded by exclusive spool ownership. */
final class PendingTerminalJournal {
    private static final String CLOSED = "sendium-closed-group:1\n";
    private final Path terminal;
    private final Path groups;
    private final PendingTerminalCodec codec = new PendingTerminalCodec();

    PendingTerminalJournal(Path root) throws IOException {
        terminal = root.resolve("terminal");
        groups = root.resolve("closed-groups");
        Files.createDirectories(terminal);
        Files.createDirectories(groups);
        requireDirectory(terminal);
        requireDirectory(groups);
    }

    void validate() throws IOException {
        forEachIntent(intent -> {
            // Decode/validate every committed intent before applying any removal.
        });
        forEachClosedGroup(group -> {
            // Validate closure markers before replay changes authoritative files.
        });
    }

    void publish(Intent intent) throws IOException {
        if (!PendingSpool.publishBytes(intentPath(intent.id()), codec.encode(intent))) {
            throw invalid("intentAlreadyExists");
        }
    }

    void forEachIntent(EntryConsumer<Intent> action) throws IOException {
        try (var entries = Files.newDirectoryStream(terminal)) {
            for (Path path : entries) {
                if (cleanTemporary(path)) {
                    continue;
                }
                String name = path.getFileName().toString();
                UUID expected = filename(name, ".cbor");
                requireFile(path);
                Intent intent;
                try {
                    intent = codec.decode(Files.readAllBytes(path));
                } catch (OutboundStorageException failure) {
                    throw new OutboundStorageException(PENDING, failure.reason(),
                            "pendingTerminalRecovery|error|intentId:" + expected + "|reason:invalidCommittedIntent", failure);
                }
                if (!intent.id().equals(expected)) {
                    throw new OutboundStorageException(PENDING, UNAVAILABLE,
                            "pendingTerminalRecovery|error|intentId:" + expected + "|reason:intentIdentityMismatch");
                }
                action.accept(intent);
            }
        }
    }

    void finish(UUID intent) throws IOException {
        Files.delete(intentPath(intent));
    }

    boolean hasIntents() throws IOException {
        try (var entries = Files.newDirectoryStream(terminal)) {
            return entries.iterator().hasNext();
        }
    }

    void closeGroup(UUID group) throws IOException {
        Path target = groupPath(group);
        Files.createDirectories(target.getParent());
        requireDirectory(target.getParent());
        PendingSpool.publishBytes(target, CLOSED.getBytes(StandardCharsets.UTF_8));
        validateMarker(target, group);
    }

    boolean isClosed(UUID group) throws IOException {
        Path path = groupPath(group);
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        validateMarker(path, group);
        return true;
    }

    void forgetGroup(UUID group) throws IOException {
        Path path = groupPath(group);
        if (!Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            validateMarker(path, group);
            Files.delete(path);
        }
    }

    void forEachClosedGroup(EntryConsumer<UUID> action) throws IOException {
        try (var buckets = Files.newDirectoryStream(groups)) {
            for (Path bucket : buckets) {
                if (!bucket.getFileName().toString().matches("[0-9a-f]{2}")) {
                    throw invalid("invalidClosureBucket");
                }
                requireDirectory(bucket);
                try (var entries = Files.newDirectoryStream(bucket)) {
                    for (Path entry : entries) {
                        if (cleanTemporary(entry)) {
                            continue;
                        }
                        UUID group = filename(entry.getFileName().toString(), ".closed");
                        if (!groupPath(group).equals(entry)) {
                            throw invalid("misplacedClosureMarker");
                        }
                        validateMarker(entry, group);
                        action.accept(group);
                    }
                }
            }
        }
    }

    private Path intentPath(UUID id) {
        return terminal.resolve(id + ".cbor");
    }

    private Path groupPath(UUID id) {
        String name = id.toString();
        return groups.resolve(name.substring(0, 2)).resolve(name + ".closed");
    }

    private static UUID filename(String name, String suffix) {
        if (name.length() != 36 + suffix.length() || !name.endsWith(suffix)) {
            throw invalid("invalidMetadataFilename");
        }
        try {
            UUID id = UUID.fromString(name.substring(0, 36));
            if (!name.equals(id + suffix)) {
                throw invalid("invalidMetadataFilename");
            }
            return id;
        } catch (IllegalArgumentException failure) {
            throw invalid("invalidMetadataFilename");
        }
    }

    private static void validateMarker(Path path, UUID group) {
        try {
            requireFile(path);
            if (Files.size(path) != CLOSED.length() || !Files.readString(path).equals(CLOSED)) {
                throw invalid("invalidClosureMarker");
            }
        } catch (IOException | OutboundStorageException failure) {
            throw new OutboundStorageException(PENDING, UNAVAILABLE,
                    "pendingTerminalRecovery|error|groupId:" + group + "|reason:invalidClosureMarker", failure);
        }
    }

    private static boolean cleanTemporary(Path path) throws IOException {
        if (!PendingSpool.temporary(path.getFileName().toString())) {
            return false;
        }
        requireFile(path);
        Files.delete(path);
        return true;
    }

    private static void requireFile(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw invalid("regularFileRequired");
        }
    }

    private static void requireDirectory(Path path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw invalid("realDirectoryRequired");
        }
    }

    private static OutboundStorageException invalid(String reason) {
        return new OutboundStorageException(PENDING, UNAVAILABLE, "pendingTerminalRecovery|error|reason:" + reason);
    }

    @FunctionalInterface
    interface EntryConsumer<T> {
        void accept(T value) throws IOException;
    }
}
