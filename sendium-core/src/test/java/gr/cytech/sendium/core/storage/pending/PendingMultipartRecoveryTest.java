package gr.cytech.sendium.core.storage.pending;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.PendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemoryPendingMessageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingMultipartRecoveryTest {
    @Test
    void reconstructsRecordedGroupAssignments() {
        try (var fixture = new Fixture()) {
            SourceId a = fixture.part(1, 3, "A");
            SourceId b = fixture.part(1, 3, "B");
            SourceId c = fixture.part(2, 3, "C");
            SourceId d = fixture.part(3, 3, "D");
            fixture.recover(2);
            assertThat(fixture.projections.keySet()).containsExactly(Set.of(a, c, d));
            assertThat(fixture.projections.get(Set.of(a, c, d)).body).isEqualTo("ACD");
            assertThat(fixture.bindings).doesNotContainKey(b);
            assertThat(fixture.port.find(b)).isPresent();
            assertThat(fixture.recovery.isReady()).isTrue();
            verify(fixture.port, never()).admitPart(any(), any(), any());
        }
    }

    @Test
    void downtimeCountsTowardDeadline() {
        try (var fixture = new Fixture()) {
            SourceId source = fixture.part(1, 3, "part");
            fixture.clock.now = fixture.clock.now.plusSeconds(50);
            fixture.recover(5);
            assertThat(fixture.projections).isEmpty();
            assertThat(fixture.scheduler.active()).singleElement().satisfies(task ->
                    assertThat(task.due).isEqualTo(Instant.parse("2026-10-20T12:01:00Z")));
            assertThat(fixture.payloadReads).isZero();
            fixture.clock.now = fixture.clock.now.plusSeconds(10);
            fixture.scheduler.runDue();
            assertThat(fixture.projections).containsKey(Set.of(source));
        }
    }

    @Test
    void releasesExpiredReceivedSources() {
        try (var fixture = new Fixture()) {
            SourceId first = fixture.part(1, 3, "first");
            SourceId third = fixture.part(3, 3, "third");
            fixture.clock.now = fixture.clock.now.plusSeconds(120);
            fixture.recover(1);
            assertThat(fixture.projections.keySet()).containsExactlyInAnyOrder(Set.of(first), Set.of(third));
            assertThat(fixture.projections.get(Set.of(first)).binheader).isEqualTo("0500037F0301");
            assertThat(fixture.scheduler.active()).isEmpty();
        }
    }

    @Test
    void aggregateOwnsSourcesExclusively() {
        try (var fixture = new Fixture()) {
            SourceId first = fixture.part(1, 2, "one");
            SourceId second = fixture.part(2, 2, "two");
            fixture.clock.now = fixture.clock.now.plusSeconds(120);
            fixture.recover(1);
            Set<SourceId> sources = Set.of(first, second);
            assertThat(fixture.bindings).containsEntry(first, sources).containsEntry(second, sources);
            assertThat(fixture.projections).hasSize(1);
            assertThat(fixture.projections.get(sources).reassembledParts)
                    .containsExactly(first.value().toString(), second.value().toString());
            fixture.recovery.accepted(first);
            fixture.recovery.accepted(second);
            assertThat(fixture.projections).hasSize(1);
            assertThat(fixture.records).hasSize(2);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replayedPublicationDoesNotDuplicateWork(boolean commitBeforeFailure) {
        try (var fixture = new Fixture()) {
            SourceId first = fixture.part(1, 2, "one");
            SourceId second = fixture.part(2, 2, "two");
            fixture.seal();
            AtomicBoolean fail = new AtomicBoolean(true);
            BiConsumer<Set<SourceId>, StandardMessage> publish = (sources, message) -> {
                if (fail.getAndSet(false)) {
                    if (commitBeforeFailure) {
                        message.body = "first prepared value";
                        fixture.port.makeHeldReady(sources, message);
                    }
                    throw new OutboundStorageException(OutboundStage.Role.PENDING,
                            OutboundStorageException.Reason.UNAVAILABLE, "Publication unavailable");
                }
                fixture.port.makeHeldReady(sources, message);
            };
            fixture.recovery = new PendingMultipartRecovery<>(fixture.port, fixture.clock, fixture.scheduler, publish);
            assertThatThrownBy(() -> fixture.recovery.reconstruct(1)).isInstanceOf(OutboundStorageException.class);
            assertThat(fixture.recovery.isReady()).isFalse();
            fixture.recovery.reconstruct(1);
            fixture.recovery.reconstruct(2);
            assertThat(fixture.projections).hasSize(1);
            assertThat(fixture.projections.get(Set.of(first, second)).body)
                    .isEqualTo(commitBeforeFailure ? "first prepared value" : "onetwo");
            assertThat(fixture.committedPublications).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidRecordPreventsRecoveryReadiness(boolean unknownVersion) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.part(1, 2, "one");
            SourceId second = fixture.part(2, 2, "two");
            fixture.seal();
            if (unknownVersion) {
                var cbor = new ObjectMapper(new CBORFactory());
                var node = (ObjectNode) cbor.readTree(fixture.records.get(second));
                node.put("schemaVersion", 999);
                fixture.records.put(second, cbor.writeValueAsBytes(node));
            } else {
                fixture.records.put(second, new byte[]{(byte) 255});
            }
            fixture.recovery = new PendingMultipartRecovery<>(fixture.port, fixture.clock, fixture.scheduler, fixture.port::makeHeldReady);
            assertThatThrownBy(() -> fixture.recovery.reconstruct(1)).isInstanceOf(PendingRecordException.class);
            assertThat(fixture.recovery.isReady()).isFalse();
            assertThat(fixture.records).hasSize(2);
            assertThat(fixture.projections).isEmpty();
            assertThat(fixture.scheduler.active()).isEmpty();
        }
    }

    @Test
    void reconstructsFromBoundedPages() {
        try (var fixture = new Fixture()) {
            for (int index = 0; index < 23; index++) {
                fixture.part(1, 2, "held-" + index);
            }
            fixture.recover(5);
            assertThat(fixture.maximumPage).isLessThanOrEqualTo(5);
            assertThat(fixture.pages).isEqualTo(6);
            assertThat(fixture.payloadReads).isZero();
            assertThat(fixture.scheduler.active()).hasSize(23);
        }
    }

    @Test
    void restartRebuildsRatherThanRememberingAnEarlierRelease() {
        try (var original = new Fixture(); var restarted = new Fixture()) {
            SourceId first = original.part(1, 2, "one");
            SourceId second = original.part(2, 2, "two");
            original.seal();
            original.port.makeHeldReady(Set.of(first), original.port.find(first).orElseThrow());
            assertThat(original.projections).containsKey(Set.of(first));

            // Only accepted-source CBOR is retained; prior runtime release decisions are deliberately not copied.
            restarted.records.putAll(original.records);
            original.groups.forEach((group, sources) -> restarted.groups.put(group, List.copyOf(sources)));
            restarted.sealed = true;
            restarted.recover(1);
            assertThat(restarted.projections.keySet()).containsExactly(Set.of(first, second));
            assertThat(restarted.projections.get(Set.of(first, second)).body).isEqualTo("onetwo");
        }
    }

    @Test
    void expiryRetriesInterruptedPublicationWithinTheRunningProcess() {
        try (var fixture = new Fixture()) {
            SourceId first = fixture.part(1, 3, "first");
            SourceId third = fixture.part(3, 3, "third");
            fixture.recover(1);
            AtomicBoolean unavailable = new AtomicBoolean(true);
            doAnswer(call -> {
                fixture.publish(call.getArgument(0), call.getArgument(1));
                if (unavailable.getAndSet(false)) {
                    throw new OutboundStorageException(OutboundStage.Role.PENDING,
                            OutboundStorageException.Reason.UNAVAILABLE, "Publication acknowledgement unavailable");
                }
                return null;
            }).when(fixture.port).makeHeldReady(any(), any());
            fixture.clock.now = fixture.clock.now.plusSeconds(60);
            fixture.scheduler.runDue();
            assertThat(fixture.recovery.isReady()).isTrue();
            assertThat(fixture.projections).hasSize(1);
            fixture.clock.now = fixture.clock.now.plusSeconds(1);
            fixture.scheduler.runDue();
            assertThat(fixture.projections.keySet()).containsExactlyInAnyOrder(Set.of(first), Set.of(third));
            assertThat(fixture.committedPublications).isEqualTo(2);
            assertThat(fixture.scheduler.active()).isEmpty();
        }
    }

    @Test
    void memoryGroupLookupRetainsOnlyUncompletedSourceHandles() {
        try (var pending = new MemoryPendingMessageStore<StandardMessage>(2, value -> PendingMessageData.capture(value).restore())) {
            pending.open();
            var first = new StandardMessage();
            var firstId = new SourceId(UUID.randomUUID());
            first.serial = firstId.value().toString();
            first.binheader = "0500037F0201";
            var membership = pending.admitPart(firstId, first, Duration.ofSeconds(60));
            var second = PendingMessageData.capture(first).restore();
            var secondId = new SourceId(UUID.randomUUID());
            second.serial = secondId.value().toString();
            second.binheader = "0500037F0202";
            pending.admitPart(secondId, second, Duration.ofSeconds(60));
            pending.makeHeldReady(Set.of(firstId, secondId), PendingMultipartRecovery.assemble(List.of(first, second)));
            assertThat(pending.multipartSources(membership.groupId())).containsExactly(firstId, secondId);
            pending.complete(Set.of(firstId));
            assertThat(pending.multipartSources(membership.groupId())).containsExactly(secondId);
            pending.complete(Set.of(secondId));
            assertThat(pending.multipartSources(membership.groupId())).isEmpty();
        }
    }

    @Test
    void corruptionAfterStartupStopsRecordDrivenProcessing() {
        try (var fixture = new Fixture()) {
            SourceId source = fixture.part(1, 3, "retained");
            fixture.recover(1);
            fixture.records.put(source, new byte[]{(byte) 255});
            assertThatThrownBy(() -> fixture.recovery.accepted(source)).isInstanceOf(PendingRecordException.class);
            assertThat(fixture.recovery.isReady()).isFalse();
            assertThat(fixture.scheduler.active()).isEmpty();
            assertThat(fixture.projections).isEmpty();
            assertThat(fixture.records).containsKey(source);
        }
    }

    @Test
    void aMissingGroupIndexCannotSilentlyHideAcceptedSources() {
        try (var fixture = new Fixture()) {
            SourceId source = fixture.part(1, 3, "retained");
            fixture.seal();
            fixture.groups.clear();
            fixture.recovery = new PendingMultipartRecovery<>(fixture.port, fixture.clock, fixture.scheduler, fixture.port::makeHeldReady);
            assertThatThrownBy(() -> fixture.recovery.reconstruct(1)).isInstanceOf(OutboundStorageException.class);
            assertThat(fixture.recovery.isReady()).isFalse();
            assertThat(fixture.records).containsKey(source);
            assertThat(fixture.projections).isEmpty();
            assertThat(fixture.scheduler.active()).isEmpty();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final TestClock clock = new TestClock();
        private final Timer scheduler = new Timer(clock);
        private final PendingRecordCodec codec = new PendingRecordCodec();
        private final MemoryPendingMessageStore<StandardMessage> original = new MemoryPendingMessageStore<>(100,
                value -> PendingMessageData.capture(value).restore(), value -> Instant.MIN, clock);
        private final Map<SourceId, byte[]> records = new HashMap<>();
        private final Map<UUID, List<SourceId>> groups = new HashMap<>();
        private final Map<SourceId, Set<SourceId>> bindings = new HashMap<>();
        private final Map<Set<SourceId>, StandardMessage> projections = new LinkedHashMap<>();
        private final PendingMessageStore<StandardMessage> port;
        private PendingMultipartRecovery<StandardMessage> recovery;
        private int pages;
        private int maximumPage;
        private int payloadReads;
        private int committedPublications;
        private boolean sealed;

        @SuppressWarnings("unchecked")
        private Fixture() {
            original.open();
            port = mock(PendingMessageStore.class);
            when(port.sourcesAfter(any(), anyInt())).thenAnswer(call -> {
                pages++;
                SourceId after = call.getArgument(0);
                int limit = call.getArgument(1);
                var page = records.keySet().stream().sorted(Comparator.comparing(SourceId::value))
                        .filter(id -> after == null || id.value().compareTo(after.value()) > 0).limit(limit).toList();
                maximumPage = Math.max(maximumPage, page.size());
                return page;
            });
            when(port.findMultipart(any())).thenAnswer(call -> Optional.ofNullable(read(call.getArgument(0)).multipart()));
            when(port.find(any())).thenAnswer(call -> {
                payloadReads++;
                return Optional.of(read(call.getArgument(0)).message().restore());
            });
            when(port.multipartSources(any())).thenAnswer(call -> List.copyOf(groups.getOrDefault(call.getArgument(0), List.of())));
            doAnswer(call -> {
                publish(call.getArgument(0), call.getArgument(1));
                return null;
            }).when(port).makeHeldReady(any(), any());
        }

        private void publish(Set<SourceId> requested, StandardMessage message) {
            Set<SourceId> sources = Set.copyOf(requested);
            for (SourceId source : sources) {
                if (bindings.containsKey(source) && !bindings.get(source).equals(sources)) {
                    throw new OutboundStorageException(OutboundStage.Role.PENDING,
                            OutboundStorageException.Reason.OWNERSHIP_CONFLICT, "Conflicting runtime publication");
                }
            }
            if (!projections.containsKey(sources)) {
                committedPublications++;
                projections.put(sources, PendingMessageData.capture(message).restore());
                sources.forEach(source -> bindings.put(source, sources));
            }
        }

        private SourceId part(int ordinal, int total, String body) {
            StandardMessage message = new StandardMessage();
            SourceId source = new SourceId(UUID.randomUUID());
            message.serial = source.value().toString();
            message.owner_id = "account";
            message.systemId = "system";
            message.from = "sender";
            message.to = "recipient";
            message.body = body;
            message.binheader = "0500037F" + String.format("%02X%02X", total, ordinal);
            original.admitPart(source, message, Duration.ofSeconds(60));
            return source;
        }

        private void seal() {
            if (sealed) {
                return;
            }
            for (SourceId source : original.sourcesAfter(null, 100)) {
                var membership = original.findMultipart(source).orElseThrow();
                records.put(source, codec.encode(new PendingSourceRecord(source, PendingSourceRecord.Disposition.HELD,
                        PendingMessageData.capture(original.find(source).orElseThrow()), membership)));
                groups.computeIfAbsent(membership.groupId(), ignored -> new ArrayList<>()).add(source);
            }
            original.close(); // Only the copied codec records feed recovery, not a reopened memory store.
            sealed = true;
        }

        private PendingSourceRecord read(SourceId source) {
            return codec.decode(records.get(source));
        }

        private void recover(int pageSize) {
            seal();
            recovery = new PendingMultipartRecovery<>(port, clock, scheduler, port::makeHeldReady);
            recovery.reconstruct(pageSize);
        }

        @Override
        public void close() {
            if (recovery != null) {
                recovery.close();
            }
            scheduler.shutdownNow();
            original.close();
        }
    }

    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-20T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class Timer extends ScheduledThreadPoolExecutor {
        private final TestClock clock;
        private final List<Task> tasks = new ArrayList<>();

        private Timer(TestClock clock) {
            super(1);
            this.clock = clock;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            var task = new Task(command, clock.now.plusNanos(unit.toNanos(delay)));
            tasks.add(task);
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            when(future.cancel(false)).thenAnswer(call -> {
                task.cancelled = true;
                return true;
            });
            return future;
        }

        private List<Task> active() {
            return tasks.stream().filter(task -> !task.cancelled).toList();
        }

        private void runDue() {
            for (Task task : List.copyOf(active())) {
                if (!task.due.isAfter(clock.now)) {
                    task.cancelled = true;
                    task.command.run();
                }
            }
        }
    }

    private static final class Task {
        private final Runnable command;
        private final Instant due;
        private boolean cancelled;

        private Task(Runnable command, Instant due) {
            this.command = command;
            this.due = due;
        }
    }
}
