package gr.cytech.sendium.core.outbound;

import gr.cytech.sendium.core.message.DlrReturnMetadata;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Assignment;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.Routed;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.file.FilePendingMessageStore;
import gr.cytech.sendium.core.storage.file.FileRoutedWorkStore;
import gr.cytech.sendium.core.storage.file.FileSelectedRouterStore;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import gr.cytech.sendium.core.worker.MemoryDlrReservations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

@Timeout(30)
class MemoryDlrRecoveryPriorityTest {
    @TempDir Path root;

    @ParameterizedTest
    @EnumSource(Stage.class)
    void startupRestoresBeforeConcurrentAdmission(Stage stage) throws Exception {
        seed(List.of(message(true), message(true)), "provider", stage);
        try (var harness = new Harness(3); var threads = Executors.newFixedThreadPool(2)) {
            var scanEntered = new CountDownLatch(1);
            var releaseScan = new CountDownLatch(1);
            var admissionEntered = new CountDownLatch(1);
            var first = new AtomicBoolean(true);
            // Pause before the existing pending open returns, so the old baseline also
            // exercises the admission/startup race rather than skipping this latch.
            doAnswer(call -> {
                var result = call.callRealMethod();
                if (first.getAndSet(false)) {
                    scanEntered.countDown();
                    assertThat(releaseScan.await(5, TimeUnit.SECONDS)).isTrue();
                }
                return result;
            }).when(harness.pending).open();
            var startup = threads.submit(harness.coordinator::start);
            try {
                assertThat(scanEntered.await(5, TimeUnit.SECONDS)).isTrue();
                var next = message(true);
                var admission = threads.submit(() -> {
                    admissionEntered.countDown();
                    return harness.coordinator.accept(source(next), next);
                });
                assertThat(admissionEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(admission).isNotDone();
                releaseScan.countDown();
                startup.get(5, TimeUnit.SECONDS);
                admission.get(5, TimeUnit.SECONDS);
                assertThat(harness.owner.usedCapacity()).isEqualTo(3);
                assertCapacityRejection(harness);
            } finally {
                releaseScan.countDown();
            }
        }
    }

    @Test
    void exactlyFullBacklogRejectsNewDlr() {
        seed(List.of(message(true), message(true)), "provider", Stage.ROUTED);
        try (var harness = new Harness(2)) {
            harness.coordinator.start();
            assertThat(harness.owner.usedCapacity()).isEqualTo(2);
            assertCapacityRejection(harness);
        }
    }

    @Test
    void reducedLimitReservesPriorityForUnprotectedBacklog() throws Exception {
        seedTwoGroups();
        try (var harness = new Harness(3)) {
            harness.coordinator.start();
            assertThat(harness.owner.usedCapacity()).isZero();
            var first = harness.take("a");
            assertThat(harness.owner.usedCapacity()).isEqualTo(2);
            assertCapacityRejection(harness);
            assertThat(harness.coordinator.takeFromRouted("b", Duration.ZERO)).isEmpty();
            assertThat(harness.owner.usedCapacity()).isEqualTo(2);
            assertThat(harness.pending.sourcesAfter(null, 32)).hasSize(4);
            assertThat(first.destination()).isEqualTo("a");
        }
    }

    @Test
    void lastProtectedObligationOpensOnlySpareCapacity() throws Exception {
        seedTwoGroups();
        try (var harness = new Harness(3)) {
            harness.coordinator.start();
            harness.complete(harness.take("a"));
            assertThat(harness.owner.usedCapacity()).isZero();
            assertCapacityRejection(harness);
            var last = harness.take("b");
            var next = message(true);
            harness.coordinator.accept(source(next), next);
            assertThat(harness.owner.usedCapacity()).isEqualTo(3);
            assertThat(harness.pending.find(source(last.message()))).isPresent();
            assertCapacityRejection(harness);
        }
    }

    @Test
    void repeatedProtectionDoesNotDebitRecoveryTwice() throws Exception {
        var first = message(true);
        first.binheader = "0500037F0201";
        var duplicate = message(true);
        duplicate.binheader = first.binheader;
        var last = message(false);
        last.binheader = "0500037F0202";
        seed(List.of(first, duplicate, last), "a", Stage.ROUTED);
        seed(List.of(message(true), message(true)), "b", Stage.ROUTED);
        try (var harness = new Harness(3)) {
            harness.coordinator.start();
            var work = harness.take("a");
            assertThat(work.message().reassembledParts).hasSize(2);
            var guard = harness.coordinator.providerPreparation(work.id()).orElseThrow();
            guard.accept(work.message());
            guard.accept(work.message());
            harness.coordinator.returnToRouted(work);
            var repeated = harness.take("a");
            guard.accept(repeated.message());
            assertThat(harness.owner.usedCapacity()).isEqualTo(2);
            assertCapacityRejection(harness);
            harness.complete(repeated);
            harness.take("b");
            var next = message(true);
            harness.coordinator.accept(source(next), next);
            assertThat(harness.owner.usedCapacity()).isEqualTo(3);
        }
    }

    @Test
    void filterRequirementCannotConsumeRecoveryCapacity() throws Exception {
        seedTwoGroups();
        try (var harness = new Harness(3)) {
            harness.coordinator.start();
            var plain = message(false);
            harness.coordinator.accept(source(plain), plain);
            harness.coordinator.selectToRouter(1);
            var selected = harness.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            harness.coordinator.recordToRouted(selected, new Destination<>("filtered", selected.message()));
            var work = harness.take("filtered");
            var guard = harness.coordinator.providerPreparation(work.id()).orElseThrow();
            work.message().serial = UUID.randomUUID().toString();
            work.message().dlrReturnMetadata = metadata();
            assertThatThrownBy(() -> guard.accept(work.message())).isInstanceOf(OutboundDlrPolicy.Blocked.class);
            // Replacement attempts use this same guard without taking routed work again.
            assertThatThrownBy(() -> guard.accept(work.message())).isInstanceOf(OutboundDlrPolicy.Blocked.class);
            assertThat(harness.owner.usedCapacity()).isZero();
            harness.complete(harness.take("a"));
            harness.take("b");
            guard.accept(work.message());
            assertThat(harness.owner.usedCapacity()).isEqualTo(3);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 10})
    void startupScanUsesBoundedPages(int maximum) {
        var messages = new ArrayList<StandardMessage>();
        for (int i = 0; i < 7; i++) {
            messages.add(message(true));
        }
        seed(messages, "provider", Stage.HELD);
        try (var harness = new Harness(maximum)) {
            var visited = new AtomicInteger();
            var awaitingLookup = new AtomicInteger();
            doAnswer(call -> {
                assertThat((int) call.getArgument(1)).isEqualTo(2);
                assertThat(awaitingLookup).hasValue(0);
                List<SourceId> page = (List<SourceId>) call.callRealMethod();
                assertThat(page).hasSizeLessThanOrEqualTo(2);
                awaitingLookup.set(page.size());
                visited.addAndGet(page.size());
                return page;
            }).when(harness.pending).sourcesAfter(any(), anyInt());
            doAnswer(call -> {
                var result = call.callRealMethod();
                assertThat(awaitingLookup.decrementAndGet()).isGreaterThanOrEqualTo(0);
                return result;
            }).when(harness.pending).find(any());
            harness.coordinator.start();
            assertThat(awaitingLookup).hasValue(0);
            assertThat(visited).hasValue(maximum < 7 ? 7 : 14);
            assertThat(harness.owner.usedCapacity()).isEqualTo(maximum < 7 ? 0 : 7);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void scanFailureDoesNotOpenAdmission(boolean lookupFailure) {
        seed(List.of(message(true)), "provider", Stage.ROUTED);
        try (var harness = new Harness(2)) {
            if (lookupFailure) {
                doAnswer(_ -> Optional.empty()).when(harness.pending).find(any());
            } else {
                doAnswer(_ -> {
                    throw new IllegalStateException("testRecovery|error|reason:scanFailure");
                }).when(harness.pending).sourcesAfter(any(), anyInt());
            }
            assertThatThrownBy(harness.coordinator::start).isInstanceOf(RuntimeException.class);
            assertThat(harness.coordinator.isReady()).isFalse();
            assertThatThrownBy(() -> harness.coordinator.accept(source(message(true)), message(true)))
                    .isInstanceOf(OutboundStorageException.class);
        }
    }

    @Test
    void nonDlrAdmissionRemainsEligible() {
        seedTwoGroups();
        try (var harness = new Harness(3)) {
            harness.coordinator.start();
            var plain = message(false);
            assertThat(harness.coordinator.accept(source(plain), plain)).isEqualTo(source(plain));
            assertThat(harness.owner.usedCapacity()).isZero();
            assertThat(harness.pending.find(source(plain))).isPresent();
        }
    }

    @Test
    void oversizedGroupRetainsRecoveryPriority() throws Exception {
        var parts = List.of(message(true), message(true), message(true));
        seed(parts, "provider", Stage.ROUTED);
        try (var harness = new Harness(2)) {
            harness.coordinator.start();
            assertThat(harness.coordinator.takeFromRouted("provider", Duration.ZERO)).isEmpty();
            assertThat(harness.owner.usedCapacity()).isZero();
            assertCapacityRejection(harness);
            for (var part : parts) {
                assertThat(harness.pending.find(source(part))).isPresent();
            }
            // The existing recovery test separately captures the unchanged operator diagnosis.
            var plain = message(false);
            harness.coordinator.accept(source(plain), plain);
        }
    }

    @Test
    void uncertainCleanupRetainsRecoveryPriority() throws Exception {
        seed(List.of(message(true), message(true), message(true), message(true)), "provider", Stage.SELECTED);
        try (var harness = new Harness(3)) {
            harness.coordinator.start();
            var selected = harness.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            var uncertain = new AtomicBoolean(true);
            doAnswer(call -> {
                call.callRealMethod();
                if (uncertain.getAndSet(false)) {
                    throw new IllegalStateException("testCleanup|error|reason:uncertainPublication");
                }
                return null;
            }).when(harness.pending).complete(any());
            assertThatThrownBy(() -> harness.coordinator.discard(selected.id(), CompletableFuture.completedStage(null))
                    .toCompletableFuture().join()).hasCauseInstanceOf(IllegalStateException.class);
            assertCapacityRejection(harness);
            harness.coordinator.discard(selected.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
            // A repeated completion of the retired selection must not debit the other three.
            harness.coordinator.discard(selected.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
            assertCapacityRejection(harness);
            for (int i = 0; i < 3; i++) {
                var next = harness.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
                harness.coordinator.discard(next.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
            }
            var next = message(true);
            harness.coordinator.accept(source(next), next);
            assertThat(harness.owner.usedCapacity()).isEqualTo(1);
        }
    }

    private void seedTwoGroups() {
        seed(List.of(message(true), message(true)), "a", Stage.ROUTED);
        seed(List.of(message(true), message(true)), "b", Stage.ROUTED);
    }

    private void seed(List<StandardMessage> messages, String destination, Stage stage) {
        try (var pending = new FilePendingMessageStore(root, 32);
             var selected = new FileSelectedRouterStore(pending, 32);
             var routed = new FileRoutedWorkStore(pending, 32)) {
            pending.open();
            selected.open();
            routed.open();
            for (var message : messages) {
                if (message.binheader != null) {
                    pending.admitPart(source(message), message, Duration.ofMinutes(5));
                } else if (stage == Stage.HELD) {
                    pending.admitHeld(source(message), message);
                } else {
                    pending.admit(source(message), message);
                }
            }
            if (stage == Stage.SELECTED) {
                selected.selectToRouter(32);
            } else if (stage == Stage.ROUTED) {
                var projection = PendingMessageData.capture(messages.getFirst()).restore();
                if (messages.getFirst().binheader != null) {
                    projection.binheader = null;
                    projection.reassembledParts = new ArrayList<>(List.of(messages.getFirst().serial, messages.getLast().serial));
                }
                routed.record(new Assignment<>(new SelectionId(UUID.randomUUID()),
                        messages.stream().map(MemoryDlrRecoveryPriorityTest::source).collect(Collectors.toSet()),
                        new Destination<>(destination, projection)));
            }
        }
    }

    private enum Stage { HELD, SELECTED, ROUTED }

    private final class Harness implements AutoCloseable {
        private final MemoryDlrReservations owner;
        private final FilePendingMessageStore pending = spy(new FilePendingMessageStore(root, 32));
        private final DefaultOutboundCoordinator<StandardMessage> coordinator;

        private Harness(int maximum) {
            owner = new MemoryDlrReservations(maximum);
            coordinator = new DefaultOutboundCoordinator<>(pending, new FileSelectedRouterStore(pending, 32),
                    new FileRoutedWorkStore(pending, 32, 2), 2, owner);
        }

        private Routed<StandardMessage> take(String destination) throws InterruptedException {
            return coordinator.takeFromRouted(destination, Duration.ZERO).orElseThrow();
        }

        private void complete(Routed<StandardMessage> work) {
            // No provider acceptance/receipt is recorded in this coordination-only fixture.
            coordinator.complete(work.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
        }

        @Override
        public void close() {
            coordinator.close();
        }
    }

    private static void assertCapacityRejection(Harness harness) {
        var message = message(true);
        assertThatThrownBy(() -> harness.coordinator.accept(source(message), message))
                .isInstanceOfSatisfying(OutboundStorageException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(OutboundStorageException.Reason.CAPACITY_EXCEEDED));
        assertThat(harness.pending.find(source(message))).isEmpty();
    }

    private static StandardMessage message(boolean dlr) {
        var message = new StandardMessage();
        message.serial = UUID.randomUUID().toString();
        message.binheader = null;
        message.body = "synthetic";
        if (dlr) {
            message.dlrReturnMetadata = metadata();
        }
        return message;
    }

    private static DlrReturnMetadata metadata() {
        return DlrReturnMetadata.http("account", "sender", "recipient", "https://callback.test");
    }

    private static SourceId source(StandardMessage message) {
        return new SourceId(UUID.fromString(message.serial));
    }
}
