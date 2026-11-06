package gr.cytech.sendium.core.storage.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Disposable ordered references. Call only under the owning spool lock; rebuild after interruption. */
final class DirectoryReferenceIndex {
    private final Path root;
    private final int keyLength;
    private final int leafLimit;

    DirectoryReferenceIndex(Path root, int keyLength, int leafLimit) throws IOException {
        this.root = root;
        this.keyLength = keyLength;
        this.leafLimit = leafLimit;
        if (keyLength <= 0 || keyLength % 2 != 0 || leafLimit <= 0) {
            throw new IllegalArgumentException("pendingIndexConfiguration|error|reason:invalidBounds");
        }
        Files.createDirectories(root);
    }

    void add(String key) throws IOException {
        checkKey(key);
        add(root, key, 0);
    }

    private void add(Path node, String key, int depth) throws IOException {
        Path children = node.resolve("children");
        if (Files.isDirectory(children, LinkOption.NOFOLLOW_LINKS)) {
            Path child = children.resolve(key.substring(depth, depth + 2));
            Files.createDirectories(child);
            add(child, key, depth + 2);
            return;
        }
        Path reference = node.resolve(key);
        if (!Files.exists(reference, LinkOption.NOFOLLOW_LINKS)) {
            Files.createFile(reference);
        }
        List<String> entries = names(node, leafLimit + 1);
        if (entries.size() > leafLimit) {
            Files.createDirectory(children);
            for (String entry : entries) {
                Path child = children.resolve(entry.substring(depth, depth + 2));
                Files.createDirectories(child);
                add(child, entry, depth + 2);
                Files.delete(node.resolve(entry));
            }
        }
    }

    void remove(String key) throws IOException {
        checkKey(key);
        remove(root, key, 0);
    }

    private boolean remove(Path node, String key, int depth) throws IOException {
        Path children = node.resolve("children");
        if (Files.isDirectory(children, LinkOption.NOFOLLOW_LINKS)) {
            Path child = children.resolve(key.substring(depth, depth + 2));
            if (Files.exists(child, LinkOption.NOFOLLOW_LINKS) && remove(child, key, depth + 2)) {
                Files.delete(child);
            }
            if (names(children, 256).isEmpty()) {
                Files.delete(children);
            }
        } else {
            Files.deleteIfExists(node.resolve(key));
        }
        return names(node, Math.max(leafLimit, 256)).isEmpty();
    }

    List<String> after(String afterExclusive, int limit) throws IOException {
        if (limit <= 0) {
            throw new IllegalArgumentException("pendingIndexTraversal|error|reason:nonPositiveLimit");
        }
        if (afterExclusive != null) {
            checkKey(afterExclusive);
        }
        var result = new ArrayList<String>();
        collect(root, "", afterExclusive, limit, result);
        return List.copyOf(result);
    }

    private void collect(Path node, String prefix, String after, int limit, List<String> result) throws IOException {
        if (after != null && prefix.compareTo(after.substring(0, prefix.length())) < 0) {
            return;
        }
        Path children = node.resolve("children");
        if (Files.isDirectory(children, LinkOption.NOFOLLOW_LINKS)) {
            for (String child : names(children, 256)) {
                collect(children.resolve(child), prefix + child, after, limit, result);
                if (result.size() == limit) {
                    return;
                }
            }
        } else {
            for (String key : names(node, leafLimit)) {
                if (after == null || key.compareTo(after) > 0) {
                    result.add(key);
                    if (result.size() == limit) {
                        return;
                    }
                }
            }
        }
    }

    private static List<String> names(Path directory, int bound) throws IOException {
        var names = new ArrayList<String>();
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (names.size() == bound) {
                    throw new IOException("pendingIndexTraversal|error|reason:directoryBoundExceeded");
                }
                names.add(entry.getFileName().toString());
            }
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }

    private void checkKey(String key) {
        if (key == null || key.length() != keyLength || !key.matches("[0-9a-f]+")) {
            throw new IllegalArgumentException("pendingIndexTraversal|error|reason:invalidKey");
        }
    }
}
