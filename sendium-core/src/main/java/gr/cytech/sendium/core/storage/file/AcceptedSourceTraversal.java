package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Bounded reader for the accepted/3, /2, /1 layout.
 * The owner must serialize traversal, admission, deletion and active-source reservations with its lock.
 * Retains at most three directory streams and one bounded result page, never a whole-spool ID set.
 */
final class AcceptedSourceTraversal implements AutoCloseable {
    private final BucketCursor[] priorities;
    private int nextPriority;
    private boolean closed;

    AcceptedSourceTraversal(Path accepted) {
        Objects.requireNonNull(accepted, "acceptedTraversal|error|reason:missingRoot");
        priorities = new BucketCursor[]{new BucketCursor(accepted, 3), new BucketCursor(accepted, 2), new BucketCursor(accepted, 1)};
    }

    /**
     * Visit at most scanBudget bucket probes/source entries and return at most limit eligible IDs.
     * Empty pages are not exhaustion unless roundComplete is true. Continue partial scans next call.
     * A new result-bearing batch starts with priority 3; no FIFO order is promised within a priority.
     * The caller reserves returned IDs before another poll so retained active work is not selected twice.
     */
    Page poll(int limit, int scanBudget, Predicate<SourceId> eligible) throws IOException {
        return scan(limit, scanBudget, eligible, 0);
    }

    /** Fixed runtime ready counts let the owner avoid probing known-empty priorities. */
    Page pollPriority(int priority, int limit, int scanBudget, Predicate<SourceId> eligible) throws IOException {
        if (priority < 1 || priority > 3) {
            throw new IllegalArgumentException("acceptedTraversal|error|reason:unsupportedPriority");
        }
        nextPriority = 3 - priority;
        return scan(limit, scanBudget, eligible, priority);
    }

    void restartPriority(int priority) throws IOException {
        if (closed || priority < 1 || priority > 3) {
            throw new IllegalStateException("acceptedTraversal|error|reason:invalidRestartState");
        }
        BucketCursor cursor = priorities[3 - priority];
        cursor.finishBucket();
        cursor.bucket = 0;
    }

    private Page scan(int limit, int scanBudget, Predicate<SourceId> eligible, int priority) throws IOException {
        if (closed || limit <= 0 || scanBudget <= 0) {
            throw new IllegalStateException("acceptedTraversal|error|reason:invalidPollState");
        }
        Objects.requireNonNull(eligible, "acceptedTraversal|error|reason:missingEligibility");
        var found = new ArrayList<Candidate>();
        int steps = 0;
        boolean complete = false;
        try {
            while (steps < scanBudget && found.size() < limit) {
                BucketCursor cursor = priorities[nextPriority];
                if (priority != 0 && cursor.priority != priority) {
                    if (++nextPriority == priorities.length) {
                        nextPriority = 0;
                        complete = true;
                        break;
                    }
                    continue;
                }
                if (cursor.stream == null) {
                    if (cursor.bucket == 256) {
                        cursor.bucket = 0;
                        if (++nextPriority == priorities.length) {
                            nextPriority = 0;
                            complete = true;
                            break;
                        }
                        continue;
                    }
                    cursor.openBucket();
                    steps++;
                } else if (cursor.entries.hasNext()) {
                    Path path = cursor.entries.next();
                    steps++;
                    Candidate candidate = cursor.candidate(path);
                    if (candidate != null && eligible.test(candidate.source())) {
                        found.add(candidate);
                    }
                } else {
                    cursor.finishBucket();
                }
            }
            if (!found.isEmpty()) {
                nextPriority = 0;
            }
            return new Page(found, steps, complete);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        IOException failure = null;
        for (BucketCursor cursor : priorities) {
            try {
                cursor.finishBucket();
            } catch (IOException cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    record Candidate(SourceId source, int priority) {
    }

    record Page(List<Candidate> candidates, int steps, boolean roundComplete) {
        Page {
            candidates = List.copyOf(candidates);
        }
    }

    private static final class BucketCursor {
        private final Path root;
        private final int priority;
        private int bucket;
        private DirectoryStream<Path> stream;
        private Iterator<Path> entries;
        private String prefix;

        private BucketCursor(Path accepted, int priority) {
            root = accepted.resolve(Integer.toString(priority));
            this.priority = priority;
        }

        private void openBucket() throws IOException {
            // Missing/unsafe priority roots are storage failures, not an empty queue.
            if (!Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory()) {
                throw new IOException("acceptedTraversal|error|reason:invalidPriorityDirectory");
            }
            prefix = Integer.toHexString(0x100 | bucket).substring(1);
            Path path = root.resolve(prefix);
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException missing) {
                bucket++;
                return;
            }
            if (!attributes.isDirectory()) {
                throw new IOException("acceptedTraversal|error|reason:invalidBucketDirectory");
            }
            stream = Files.newDirectoryStream(path);
            entries = stream.iterator();
        }

        private Candidate candidate(Path path) throws IOException {
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException removed) {
                return null; // A retained iterator can have buffered an entry removed between polls.
            }
            String filename = path.getFileName().toString();
            if (!attributes.isRegularFile() || !filename.endsWith(".cbor")) {
                throw new IOException("acceptedTraversal|error|reason:invalidSourceEntry");
            }
            String id = filename.substring(0, filename.length() - 5);
            try {
                UUID uuid = UUID.fromString(id);
                if (!uuid.toString().equals(id) || !id.startsWith(prefix)) {
                    throw new IllegalArgumentException();
                }
                return new Candidate(new SourceId(uuid), priority);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("acceptedTraversal|error|reason:invalidSourceName", invalid);
            }
        }

        private void finishBucket() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
                entries = null;
                bucket++;
            }
        }
    }
}
