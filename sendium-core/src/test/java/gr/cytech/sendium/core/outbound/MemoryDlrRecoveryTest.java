package gr.cytech.sendium.core.outbound;

import gr.cytech.sendium.core.message.DlrReturnMetadata;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Assignment;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.outbound.OutboundWork.WorkId;
import gr.cytech.sendium.core.storage.file.FilePendingMessageStore;
import gr.cytech.sendium.core.storage.file.FileRoutedWorkStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import gr.cytech.sendium.core.worker.DlrStorage;
import gr.cytech.sendium.core.worker.MemoryDlrReservations;
import gr.cytech.sendium.core.worker.MessageState;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

@Timeout(30)
class MemoryDlrRecoveryTest {
    @TempDir Path root;

    @Test
    void recoveryAcquiresMissingSourcesAtomically() throws Exception {
        var seed = seed(3, 3);
        seed(1, 1, "blocker");
        var owner = new MemoryDlrReservations(3);
        try (var harness = new Harness(owner)) {
            var blocker = harness.coordinator.takeFromRouted("blocker", Duration.ZERO).orElseThrow();
            assertThat(harness.coordinator.takeFromRouted("provider", Duration.ZERO)).isEmpty();
            assertThat(owner.usedCapacity()).isEqualTo(1);
            assertThat(harness.pending.sourcesAfter(null, 10)).containsAll(seed.sources());
            assertThat(harness.coordinator.takeFromRouted("provider", Duration.ZERO)).isEmpty();
            assertThat(owner.usedCapacity()).isEqualTo(1);
            harness.coordinator.complete(blocker.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
            var work = harness.coordinator.takeFromRouted("provider", Duration.ZERO).orElseThrow();
            assertThat(work.id()).isEqualTo(seed.work());
            assertThat(owner.usedCapacity()).isEqualTo(3);
        }
    }

    @Test
    void oversizedSourceSetStaysPendingWithDiagnosis() throws Exception {
        var seed = seed(3, 3);
        var owner = new MemoryDlrReservations(2);
        try (var capture = new Logs(); var harness = new Harness(owner)) {
            for (int i = 0; i < 5; i++) {
                assertThat(harness.coordinator.takeFromRouted("provider", Duration.ZERO)).isEmpty();
            }
            assertThat(owner.usedCapacity()).isZero();
            assertThat(harness.pending.sourcesAfter(null, 10)).containsAll(seed.sources());
            assertThat(capture.messages).singleElement().asString()
                    .contains("requiredSources:3", "maxTrackedSubmissions:2", "action:increaseMaxTrackedSubmissions");
        }
    }

    @Test
    void capacityReleaseResumesRecordedWork() throws Exception {
        var seed = seed(3, 3);
        seed(1, 1, "blocker");
        var owner = new MemoryDlrReservations(3);
        try (var harness = new Harness(owner)) {
            var blocker = harness.coordinator.takeFromRouted("blocker", Duration.ZERO).orElseThrow();
            assertThat(harness.coordinator.takeFromRouted("provider", Duration.ZERO)).isEmpty();
            assertThat(owner.usedCapacity()).isEqualTo(1);
            harness.coordinator.complete(blocker.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
            var work = harness.coordinator.takeFromRouted("provider", Duration.ZERO).orElseThrow();
            assertThat(work.id()).isEqualTo(seed.work());
            assertThat(work.destination()).isEqualTo("provider");
            assertThat(harness.coordinator.selectToRouter(10)).isZero();
            harness.coordinator.complete(work.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
            assertThat(owner.usedCapacity()).isZero();
            assertThat(harness.pending.sourcesAfter(null, 10)).isEmpty();
        }
    }

    @Test
    void sourceCleanupDoesNotReleasePendingReceipt() throws Exception {
        var seed = seed(1, 1);
        var owner = new MemoryDlrReservations(2);
        // The backend remains package-private; test-only reflection avoids a production factory
        // or exposing startup selection just to exercise this cross-package integration.
        DlrStorage storage = memoryStorage(owner);
        try (var backend = (AutoCloseable) storage; var harness = new Harness(owner)) {
            var work = harness.coordinator.takeFromRouted("provider", Duration.ZERO).orElseThrow();
            var state = new MessageState(work.message().serial, "account", "system", "sender", "recipient", "https://callback.test");
            state.setDeliveryChannel(MessageState.DeliveryChannel.HTTP);
            storage.recordProviderAccepted(state, "P", "id");
            harness.coordinator.complete(work.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
            assertThat(harness.pending.find(seed.sources().getFirst())).isEmpty();
            assertThat(owner.usedCapacity()).isEqualTo(1);
            storage.resolveDlr("P", "id", MessageState.MessageStatus.DELIVERED, 1, "0").orElseThrow();
            storage.claimDueHttpDeliveries(1);
            storage.completeDelivery(state.getGatewayMsgId(), 1);
            assertThat(owner.usedCapacity()).isZero();
        }
    }

    @Test
    void expiredIncompletePartsRetainIndependentOwnership() throws Exception {
        List<StandardMessage> parts = List.of(message(true), message(true));
        try (var pending = new FilePendingMessageStore(root, 10, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
             var routed = new FileRoutedWorkStore(pending, 10)) {
            pending.open();
            routed.open();
            for (int i = 0; i < parts.size(); i++) {
                var part = parts.get(i);
                part.binheader = "0500037F03%02X".formatted(i + 1);
                pending.admitPart(source(part), part, Duration.ofMinutes(1));
                routed.record(new Assignment<>(new SelectionId(UUID.randomUUID()), Set.of(source(part)),
                        new Destination<>("provider", part)));
            }
        }
        var owner = new MemoryDlrReservations(2);
        try (var harness = new Harness(owner)) {
            var first = harness.coordinator.takeFromRouted("provider", Duration.ZERO).orElseThrow();
            var second = harness.coordinator.takeFromRouted("provider", Duration.ZERO).orElseThrow();
            assertThat(owner.usedCapacity()).isEqualTo(2);
            harness.coordinator.complete(first.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
            assertThat(owner.usedCapacity()).isEqualTo(1);
            assertThat(harness.pending.find(source(first.message()))).isEmpty();
            assertThat(harness.pending.find(source(second.message()))).isPresent();
            harness.coordinator.complete(second.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
            assertThat(owner.usedCapacity()).isZero();
        }
    }

    @Test
    void disabledRecoveryRetainsDlrWork() throws Exception {
        var seed = seed(1, 1);
        OutboundDlrPolicy disabled = new OutboundDlrPolicy() {
            @Override
            public Reservation reserveAdmission(SourceId source, StandardMessage message) {
                return Reservation.UNCHANGED;
            }

            @Override
            public void prepareProvider(String id, Set<SourceId> required) {
                if (!required.isEmpty()) {
                    throw new Blocked(BlockReason.DISABLED, required.size(), 0);
                }
            }
        };
        try (var capture = new Logs(); var harness = new Harness(disabled)) {
            for (int i = 0; i < 5; i++) {
                assertThat(harness.coordinator.takeFromRouted("provider", Duration.ZERO)).isEmpty();
            }
            assertThat(harness.pending.sourcesAfter(null, 10)).containsAll(seed.sources());
            assertThat(capture.messages).singleElement().asString().contains("reason:disabled", "action:enableDlrHandling");
        }
    }

    @Test
    void mixedIngressRequestsDoNotChargeNonDlrParts() throws Exception {
        seed(2, 1);
        var owner = new MemoryDlrReservations(1);
        try (var harness = new Harness(owner)) {
            assertThat(harness.coordinator.takeFromRouted("provider", Duration.ZERO)).isPresent();
            assertThat(owner.usedCapacity()).isEqualTo(1);
        }
    }

    @Test
    void uncertainSourceCleanupKeepsOutboundCapacityUntilConfirmed() throws Exception {
        var seed = seed(1, 1);
        var owner = new MemoryDlrReservations(1);
        try (var harness = new Harness(owner)) {
            var work = harness.coordinator.takeFromRouted("provider", Duration.ZERO).orElseThrow();
            var first = new AtomicBoolean(true);
            doAnswer(call -> {
                call.callRealMethod();
                if (first.getAndSet(false)) {
                    throw new IllegalStateException("testCleanup|error|reason:uncertainPublication");
                }
                return null;
            }).when(harness.pending).complete(any());
            assertThatThrownBy(() -> harness.coordinator.complete(work.id(), CompletableFuture.completedStage(null))
                    .toCompletableFuture().join()).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(harness.pending.find(seed.sources().getFirst())).isEmpty();
            assertThat(owner.usedCapacity()).isEqualTo(1);
            harness.coordinator.complete(work.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
            assertThat(owner.usedCapacity()).isZero();
        }
    }

    @Test
    void recoveredMultipartIncludesSeparatelyAcceptedDuplicates() throws Exception {
        var first = message(true);
        first.binheader = "0500037F0201";
        var duplicate = message(true);
        duplicate.binheader = first.binheader;
        var second = message(true);
        second.binheader = "0500037F0202";
        try (var pending = new FilePendingMessageStore(root, 10);
             var routed = new FileRoutedWorkStore(pending, 10)) {
            pending.open();
            routed.open();
            var membership = pending.admitPart(source(first), first, Duration.ofMinutes(1));
            var duplicateMembership = pending.admitPart(source(duplicate), duplicate, Duration.ofMinutes(1));
            pending.admitPart(source(second), second, Duration.ofMinutes(1));
            assertThat(duplicateMembership.groupId()).isEqualTo(membership.groupId());
            assertThat(duplicateMembership.duplicate()).isTrue();
            first.binheader = null;
            first.reassembledParts = new ArrayList<>(List.of(first.serial, second.serial));
            routed.record(new Assignment<>(new SelectionId(UUID.randomUUID()),
                    Set.of(source(first), source(second), source(duplicate)), new Destination<>("provider", first)));
        }
        var owner = new MemoryDlrReservations(3);
        try (var harness = new Harness(owner)) {
            var work = harness.coordinator.takeFromRouted("provider", Duration.ZERO).orElseThrow();
            assertThat(owner.usedCapacity()).isEqualTo(3);
            assertThat(work.message().reassembledParts).hasSize(2);
            harness.coordinator.complete(work.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
            assertThat(owner.usedCapacity()).isZero();
        }
    }

    @Test
    void competingRecoveredGroupsCannotHoldPartialSets() throws Exception {
        var owner = new MemoryDlrReservations(3);
        var a = Set.of(new SourceId(UUID.randomUUID()), new SourceId(UUID.randomUUID()));
        var b = Set.of(new SourceId(UUID.randomUUID()), new SourceId(UUID.randomUUID()));
        String idA = UUID.randomUUID().toString();
        String idB = UUID.randomUUID().toString();
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> reserveRecovered(owner, idA, a, start));
            var second = threads.submit(() -> reserveRecovered(owner, idB, b, start));
            start.countDown();
            boolean wonA = first.get(5, TimeUnit.SECONDS);
            boolean wonB = second.get(5, TimeUnit.SECONDS);
            assertThat((wonA ? 1 : 0) + (wonB ? 1 : 0)).isEqualTo(1);
            assertThat(owner.usedCapacity()).isEqualTo(2);
            owner.releaseOutbound(wonA ? a : b);
            assertThat(owner.usedCapacity()).isZero();
            owner.prepareProvider(wonA ? idB : idA, wonA ? b : a);
            assertThat(owner.usedCapacity()).isEqualTo(2);
        }
    }

    private static boolean reserveRecovered(MemoryDlrReservations owner, String id, Set<SourceId> sources,
                                             CountDownLatch start) throws InterruptedException {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            owner.prepareProvider(id, sources);
            return true;
        } catch (OutboundDlrPolicy.Blocked blocked) {
            assertThat(blocked.blockReason()).isEqualTo(OutboundDlrPolicy.BlockReason.CAPACITY);
            return false;
        }
    }

    private Seed seed(int count, int dlrCount) {
        return seed(count, dlrCount, "provider");
    }

    private Seed seed(int count, int dlrCount, String destination) {
        var messages = new ArrayList<StandardMessage>();
        try (var pending = new FilePendingMessageStore(root, 10);
             var routed = new FileRoutedWorkStore(pending, 10)) {
            pending.open();
            routed.open();
            for (int i = 0; i < count; i++) {
                var message = message(i < dlrCount);
                messages.add(message);
                pending.admit(source(message), message);
            }
            var sources = messages.stream().map(MemoryDlrRecoveryTest::source).collect(Collectors.toSet());
            var work = routed.record(new Assignment<>(new SelectionId(UUID.randomUUID()), sources,
                    new Destination<>(destination, messages.getFirst())));
            return new Seed(work.id(), messages.stream().map(MemoryDlrRecoveryTest::source).toList());
        }
    }

    private record Seed(WorkId work, List<SourceId> sources) { }

    private final class Harness implements AutoCloseable {
        private final FilePendingMessageStore pending = spy(new FilePendingMessageStore(root, 10));
        private final DefaultOutboundCoordinator<StandardMessage> coordinator;

        private Harness(OutboundDlrPolicy policy) {
            coordinator = new DefaultOutboundCoordinator<>(pending, new MemorySelectedRouterStore<>(pending, 10),
                    new FileRoutedWorkStore(pending, 10, 2), 10, policy);
            coordinator.start();
        }

        @Override
        public void close() {
            coordinator.close();
        }
    }

    private static DlrStorage memoryStorage(MemoryDlrReservations owner) throws ReflectiveOperationException {
        var constructor = Class.forName("gr.cytech.sendium.core.worker.MemoryDlrStorage").getDeclaredConstructor(
                MemoryDlrReservations.class, Duration.class, Duration.class, Duration.class, Clock.class);
        constructor.setAccessible(true);
        return (DlrStorage) constructor.newInstance(owner, Duration.ofDays(3), Duration.ofDays(7), Duration.ofMinutes(5),
                Clock.systemUTC());
    }

    private static StandardMessage message(boolean dlr) {
        var message = new StandardMessage();
        message.serial = UUID.randomUUID().toString();
        message.body = "synthetic";
        if (dlr) {
            message.dlrReturnMetadata = DlrReturnMetadata.http("account", "sender", "recipient", "https://callback.test");
        }
        return message;
    }

    private static SourceId source(StandardMessage message) {
        return new SourceId(UUID.fromString(message.serial));
    }

    private static final class Logs extends Handler implements AutoCloseable {
        private final List<String> messages = new ArrayList<>();
        private final Logger logger = Logger.getLogger(DefaultOutboundCoordinator.class.getName());
        private final Level previous = logger.getLevel();

        private Logs() {
            logger.setLevel(Level.ALL);
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            messages.add(record instanceof ExtLogRecord ext ? ext.getFormattedMessage() : record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
            logger.setLevel(previous);
        }
    }
}
