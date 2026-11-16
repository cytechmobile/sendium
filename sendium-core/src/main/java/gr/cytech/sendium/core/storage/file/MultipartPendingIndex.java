package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.pending.MultipartKey;
import gr.cytech.sendium.core.storage.pending.MultipartMembership;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Disposable membership references. Accepted CBOR remains authoritative, including for hash collisions. */
final class MultipartPendingIndex {
    private static final int LEAF_LIMIT = 128;
    private final Path root;
    private final DirectoryReferenceIndex due;

    MultipartPendingIndex(Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root.resolve("groups"));
        Files.createDirectories(root.resolve("matching"));
        due = new DirectoryReferenceIndex(root.resolve("due"), 56, LEAF_LIMIT);
    }

    void add(MultipartMembership member) throws IOException {
        members(member.groupId()).add(FolderPendingIndex.sourceKey(member.source()));
        Path ordinals = group(member.groupId()).resolve("ordinals");
        Files.createDirectories(ordinals);
        Path original = ordinals.resolve(Integer.toString(member.ordinal()));
        String identity = member.originalSource().value().toString();
        if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.readString(original).equals(identity)) {
                throw new IOException("pendingMultipartRecovery|error|reason:conflictingOrdinalOwner");
            }
        } else {
            Files.writeString(original, identity, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
        }
        matching(member.key()).add(groupKey(member));
        if (originalCount(member.groupId()) == member.key().total()) {
            schedule(member.groupId(), Instant.MIN);
        } else if (!scheduled(member.groupId())) {
            schedule(member.groupId(), member.deadline());
        }
    }

    List<String> matchingAfter(MultipartKey key, String after, int limit) throws IOException {
        return matching(key).after(after, limit);
    }

    void closeGroup(MultipartMembership member) throws IOException {
        matching(member.key()).remove(groupKey(member));
    }

    void remove(MultipartMembership member) throws IOException {
        closeGroup(member);
        DirectoryReferenceIndex members = members(member.groupId());
        members.remove(FolderPendingIndex.sourceKey(member.source()));
        if (!member.duplicate()) {
            Files.delete(group(member.groupId()).resolve("ordinals").resolve(Integer.toString(member.ordinal())));
        }
        if (members.after(null, 1).isEmpty()) {
            finish(member.groupId());
            FolderPendingIndex.discard(group(member.groupId()));
        }
    }

    List<UUID> due(Instant now, int limit) throws IOException {
        var groups = new ArrayList<UUID>();
        for (String key : due.after(null, limit)) {
            if (dueAt(key).isAfter(now)) {
                break;
            }
            groups.add(new UUID(Long.parseUnsignedLong(key.substring(24, 40), 16), Long.parseUnsignedLong(key.substring(40), 16)));
        }
        return List.copyOf(groups);
    }

    boolean scheduled(UUID group) throws IOException {
        return dueKey(group) != null;
    }

    void schedule(UUID group, Instant when) throws IOException {
        String previous = dueKey(group);
        String key = "%016x%08x%016x%016x".formatted(when.getEpochSecond() ^ Long.MIN_VALUE, when.getNano(),
                group.getMostSignificantBits(), group.getLeastSignificantBits());
        if (key.equals(previous)) {
            return;
        }
        if (previous != null) {
            due.remove(previous);
        }
        due.add(key);
        Files.writeString(group(group).resolve("due-key"), key, StandardCharsets.UTF_8);
    }

    void finish(UUID group) throws IOException {
        String previous = dueKey(group);
        if (previous != null) {
            due.remove(previous);
            Files.delete(group(group).resolve("due-key"));
        }
    }

    private String dueKey(UUID group) throws IOException {
        Path path = group(group).resolve("due-key");
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("pendingMultipartLookup|error|reason:invalidDeadlineReference");
        }
        String key = Files.readString(path);
        if (!key.matches("[0-9a-f]{56}") ||
                !key.substring(24).equals("%016x%016x".formatted(group.getMostSignificantBits(), group.getLeastSignificantBits()))) {
            throw new IOException("pendingMultipartLookup|error|reason:invalidDeadlineReference");
        }
        dueAt(key);
        return key;
    }

    private static Instant dueAt(String key) throws IOException {
        try {
            long seconds = Long.parseUnsignedLong(key.substring(0, 16), 16) ^ Long.MIN_VALUE;
            int nanos = Integer.parseUnsignedInt(key.substring(16, 24), 16);
            if (nanos < 0 || nanos > 999_999_999) {
                throw new IllegalArgumentException();
            }
            return Instant.ofEpochSecond(seconds, nanos);
        } catch (RuntimeException failure) {
            throw new IOException("pendingMultipartLookup|error|reason:invalidDeadlineReference");
        }
    }

    List<SourceId> sourcesAfter(UUID group, SourceId after, int limit) throws IOException {
        if (Files.notExists(group(group), LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        return members(group).after(after == null ? null : FolderPendingIndex.sourceKey(after), limit)
                .stream().map(FolderPendingIndex::source).toList();
    }

    SourceId original(UUID group, int ordinal) throws IOException {
        Path path = group(group).resolve("ordinals").resolve(Integer.toString(ordinal));
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            return new SourceId(UUID.fromString(Files.readString(path)));
        } catch (IllegalArgumentException failure) {
            throw new IOException("pendingMultipartLookup|error|reason:invalidOrdinalReference");
        }
    }

    int originalCount(UUID group) throws IOException {
        int count = 0;
        try (var entries = Files.newDirectoryStream(group(group).resolve("ordinals"))) {
            for (Path entry : entries) {
                if (++count > 255 || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("pendingMultipartLookup|error|reason:invalidOrdinalDirectory");
                }
            }
        }
        return count;
    }

    static UUID groupId(String key) {
        return new UUID(Long.parseUnsignedLong(key.substring(16, 32), 16), Long.parseUnsignedLong(key.substring(32), 16));
    }

    private static String groupKey(MultipartMembership member) {
        return "%016x%016x%016x".formatted(member.groupOrder(), member.groupId().getMostSignificantBits(),
                member.groupId().getLeastSignificantBits());
    }

    private Path group(UUID id) {
        return root.resolve("groups").resolve(id.toString());
    }

    private DirectoryReferenceIndex members(UUID group) throws IOException {
        return new DirectoryReferenceIndex(group(group).resolve("members"), 32, LEAF_LIMIT);
    }

    private DirectoryReferenceIndex matching(MultipartKey key) throws IOException {
        String hash = hash(key);
        return new DirectoryReferenceIndex(root.resolve("matching").resolve(hash.substring(0, 2)).resolve(hash), 48, LEAF_LIMIT);
    }

    private static String hash(MultipartKey key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            field(digest, key.accountId());
            field(digest, key.systemId());
            field(digest, key.sender());
            field(digest, key.recipient());
            field(digest, Integer.toString(key.referenceBits()));
            field(digest, Integer.toString(key.reference()));
            field(digest, Integer.toString(key.total()));
            field(digest, key.normalizedUdh());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("pendingMultipartIndex|error|reason:sha256Unavailable", failure);
        }
    }

    private static void field(MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
        if (bytes != null) {
            digest.update(bytes);
        }
    }
}
