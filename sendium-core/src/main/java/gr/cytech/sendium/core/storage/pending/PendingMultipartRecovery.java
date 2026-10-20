package gr.cytech.sendium.core.storage.pending;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.PendingMessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/** Rebuilds runtime assembly from retained source facts; never checkpoints routing/publication decisions. */
public final class PendingMultipartRecovery<M extends StandardMessage> implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(PendingMultipartRecovery.class);
    private final PendingMessageStore<M> pending;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final BiConsumer<Set<SourceId>, M> publisher;
    private final Map<UUID, Group> groups = new HashMap<>();
    private volatile boolean ready;
    private boolean closed;

    public PendingMultipartRecovery(PendingMessageStore<M> pending, Clock clock, ScheduledExecutorService scheduler,
                                   BiConsumer<Set<SourceId>, M> publisher) {
        this.pending = Objects.requireNonNull(pending, "pending");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    /** Caller quiesces ingress/processing until successful return; no publication occurs before scan validation. */
    public synchronized void reconstruct(int pageSize) {
        if (closed || pageSize <= 0) {
            throw new IllegalStateException("Recovery requires an open component and positive page size");
        }
        ready = false;
        cancelTimers();
        groups.clear();
        try {
            SourceId cursor = null;
            while (true) {
                List<SourceId> page = pending.sourcesAfter(cursor, pageSize);
                if (page.size() > pageSize) {
                    throw inconsistent();
                }
                if (page.isEmpty()) {
                    break;
                }
                for (SourceId source : page) {
                    if (source == null || (cursor != null && source.value().compareTo(cursor.value()) <= 0)) {
                        throw inconsistent();
                    }
                    pending.findMultipart(source).ifPresent(membership -> index(source, membership));
                    cursor = source;
                }
            }
            // Only handles/metadata are sorted here, not the backlog's message payloads.
            var ordered = new ArrayList<>(groups.values());
            ordered.sort(Comparator.comparingLong(group -> group.identity.groupOrder()));
            for (Group group : ordered) {
                List<SourceId> indexed = pending.multipartSources(group.identity.groupId());
                if (indexed.size() != group.sources.size() || !Set.copyOf(indexed).equals(Set.copyOf(group.sources.values()))) {
                    throw inconsistent();
                }
            }
            for (Group group : ordered) {
                process(group);
            }
            ready = true;
        } catch (RuntimeException | Error failure) {
            cancelTimers();
            throw failure;
        }
    }

    public boolean isReady() {
        return ready;
    }

    /** Notification contains only a handle; accepted payload and current group members remain store-owned. */
    public synchronized void accepted(SourceId source) {
        if (!ready || closed) {
            throw new OutboundStorageException(OutboundStage.Role.PENDING, OutboundStorageException.Reason.UNAVAILABLE,
                    "Pending multipart reconstruction is not ready");
        }
        try {
            var membership = pending.findMultipart(Objects.requireNonNull(source, "source"));
            if (membership.isPresent()) {
                Group group = index(source, membership.orElseThrow());
                process(group);
            }
        } catch (RuntimeException failure) {
            if (invalidState(failure)) {
                ready = false;
                cancelTimers();
            }
            throw failure;
        }
    }

    private Group index(SourceId source, MultipartMembership membership) {
        if (!source.equals(membership.source())) {
            throw inconsistent();
        }
        Group group = groups.computeIfAbsent(membership.groupId(), ignored -> new Group(membership));
        group.add(membership);
        return group;
    }

    private void process(Group group) {
        // Establish expiry before reading membership: a later admission cannot join an already-expired group.
        final Instant now = clock.instant();
        List<SourceId> retained = pending.multipartSources(group.identity.groupId());
        if (retained.size() > group.identity.key().total() || Set.copyOf(retained).size() != retained.size()) {
            throw inconsistent();
        }
        group.sources.clear();
        for (SourceId source : retained) {
            var membership = pending.findMultipart(source).orElseThrow(PendingMultipartRecovery::inconsistent);
            if (!source.equals(membership.source())) {
                throw inconsistent();
            }
            group.add(membership);
        }
        if (group.sources.isEmpty()) {
            forget(group);
        } else if (group.sources.size() == group.identity.key().total()) {
            var parts = new ArrayList<M>();
            for (SourceId source : group.sources.values()) {
                parts.add(payload(source));
            }
            publisher.accept(Set.copyOf(group.sources.values()), assemble(parts));
            forget(group);
        } else if (!group.identity.deadline().isAfter(now)) {
            for (SourceId source : group.sources.values()) {
                publisher.accept(Set.of(source), payload(source));
            }
            forget(group);
        } else {
            long delay = Math.max(1, Duration.between(clock.instant(), group.identity.deadline()).toMillis());
            schedule(group, delay);
        }
    }

    private M payload(SourceId source) {
        return pending.find(source).orElseThrow(PendingMultipartRecovery::inconsistent);
    }

    private void schedule(Group group, long delayMillis) {
        if (group.timer != null) {
            group.timer.cancel(false);
        }
        group.timer = scheduler.schedule(() -> expire(group), delayMillis, TimeUnit.MILLISECONDS);
    }

    private synchronized void expire(Group expected) {
        if (closed || !ready || groups.get(expected.identity.groupId()) != expected) {
            return;
        }
        expected.timer = null;
        try {
            process(expected);
        } catch (RuntimeException failure) {
            var reason = failure instanceof OutboundStorageException storage ? storage.reason() : OutboundStorageException.Reason.UNAVAILABLE;
            LOGGER.warn("Pending multipart processing failed stage=PENDING reason={}", reason);
            if (invalidState(failure)) {
                ready = false;
                cancelTimers();
            } else {
                schedule(expected, 1000);
            }
        }
    }

    private void forget(Group group) {
        if (group.timer != null) {
            group.timer.cancel(false);
        }
        groups.remove(group.identity.groupId());
    }

    private void cancelTimers() {
        for (Group group : groups.values()) {
            if (group.timer != null) {
                group.timer.cancel(false);
            }
        }
    }

    /** Existing SMPP assembly semantics, operating only on detached runtime payloads in ordinal order. */
    public static <M extends StandardMessage> M assemble(List<M> parts) {
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("Assembly requires received parts");
        }
        M message = parts.getFirst();
        String body = parts.stream().map(part -> part.body).collect(Collectors.joining(""));
        var sourceIds = parts.stream().map(part -> part.serial).collect(Collectors.toCollection(ArrayList::new));
        message.binheader = null;
        message.body = body;
        message.reassembledParts = sourceIds;
        return message;
    }

    @Override
    public synchronized void close() {
        ready = false;
        closed = true;
        cancelTimers();
        groups.clear();
        // Scheduler and store are application-owned, not this component's resources.
    }

    private static OutboundStorageException inconsistent() {
        return new OutboundStorageException(OutboundStage.Role.PENDING, OutboundStorageException.Reason.OWNERSHIP_CONFLICT,
                "Inconsistent pending multipart recovery state");
    }

    private static boolean invalidState(RuntimeException failure) {
        return failure instanceof PendingRecordException || (failure instanceof OutboundStorageException storage &&
                storage.reason() == OutboundStorageException.Reason.OWNERSHIP_CONFLICT);
    }

    private static final class Group {
        private final MultipartMembership identity;
        private final Map<Integer, SourceId> sources = new TreeMap<>();
        private ScheduledFuture<?> timer;

        private Group(MultipartMembership identity) {
            this.identity = identity;
        }

        private void add(MultipartMembership member) {
            if (!identity.groupId().equals(member.groupId()) || !identity.key().equals(member.key()) ||
                    !identity.deadline().equals(member.deadline()) || identity.groupOrder() != member.groupOrder()) {
                throw inconsistent();
            }
            SourceId previous = sources.putIfAbsent(member.ordinal(), member.source());
            if (previous != null && !previous.equals(member.source())) {
                throw inconsistent();
            }
        }
    }
}
