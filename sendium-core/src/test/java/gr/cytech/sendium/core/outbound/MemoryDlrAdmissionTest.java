package gr.cytech.sendium.core.outbound;

import gr.cytech.sendium.core.message.DlrReturnMetadata;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.PendingMessageStore;
import gr.cytech.sendium.core.storage.file.FilePendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemoryPendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemoryRoutedWorkStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import gr.cytech.sendium.core.worker.MemoryDlrReservations;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;

import java.time.Duration;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.CAPACITY_EXCEEDED;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

@Timeout(20)
class MemoryDlrAdmissionTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @EnumSource(Admission.class)
    void concurrentAdmissionsRespectCapacity(Admission admission) throws Exception {
        var ledger = new MemoryDlrReservations(1);
        try (var first = new Harness(ledger); var second = new Harness(ledger);
             var threads = Executors.newFixedThreadPool(2)) {
            var a = message(true, 1);
            var b = message(true, 1);
            var start = new CountDownLatch(1);
            // Separate coordinator locks ensure the shared DLR owner is the capacity authority.
            var acceptedA = threads.submit(() -> acceptAfter(start, first, admission, a));
            var acceptedB = threads.submit(() -> acceptAfter(start, second, admission, b));
            start.countDown();
            boolean resultA = acceptedA.get(5, TimeUnit.SECONDS);
            boolean resultB = acceptedB.get(5, TimeUnit.SECONDS);
            assertThat((resultA ? 1 : 0) + (resultB ? 1 : 0)).isEqualTo(1);
            assertThat(first.pending.find(source(a)).isPresent()).isEqualTo(resultA);
            assertThat(second.pending.find(source(b)).isPresent()).isEqualTo(resultB);
            assertThat(ledger.usedCapacity()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(Admission.class)
    void reservationExistsBeforePendingPublication(Admission admission) {
        var ledger = new MemoryDlrReservations(1);
        try (var harness = new Harness(ledger)) {
            stubAdmission(harness, admission, call -> {
                assertThat(ledger.usedCapacity()).isEqualTo(1);
                return call.callRealMethod();
            });
            var message = message(true, 1);
            admission.accept(harness, message);
            assertThat(harness.pending.find(source(message))).isPresent();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void multipartSourcesCountIndividually(boolean duplicateOrdinal) {
        var ledger = new MemoryDlrReservations(2);
        try (var harness = new Harness(ledger)) {
            var first = message(true, 1);
            var second = message(true, duplicateOrdinal ? 1 : 2);
            var rejected = message(true, 3);
            var membership = harness.coordinator.acceptPart(source(first), first, Duration.ofMinutes(1));
            var next = harness.coordinator.acceptPart(source(second), second, Duration.ofMinutes(1));
            assertThat(next.groupId()).isEqualTo(membership.groupId());
            assertThat(next.duplicate()).isEqualTo(duplicateOrdinal);
            assertThat(ledger.usedCapacity()).isEqualTo(2);
            assertThat(harness.coordinator.acceptPart(source(first), first, Duration.ofMinutes(1))).isEqualTo(membership);
            assertCapacityRejection(() -> Admission.PART.accept(harness, rejected));
            assertThat(harness.pending.find(source(rejected))).isEmpty();
            assertThat(ledger.usedCapacity()).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @EnumSource(Admission.class)
    void confirmedAbsenceReleasesOnlyNewReservation(Admission admission) {
        var ledger = new MemoryDlrReservations(2);
        try (var harness = new Harness(ledger)) {
            var retained = message(true, 1);
            admission.accept(harness, retained);
            var failure = unavailable();
            stubAdmission(harness, admission, call -> { throw failure; });
            var absent = message(true, 2);
            assertThatThrownBy(() -> admission.accept(harness, absent)).isSameAs(failure);
            assertThat(harness.pending.find(source(absent))).isEmpty();
            assertThat(ledger.usedCapacity()).isEqualTo(1);
            assertThatThrownBy(() -> admission.accept(harness, retained)).isSameAs(failure);
            assertThat(harness.pending.find(source(retained))).isPresent();
            assertThat(ledger.usedCapacity()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(Admission.class)
    void uncertainPublicationRetainsReservation(Admission admission) {
        for (Uncertainty uncertainty : Uncertainty.values()) {
            var ledger = new MemoryDlrReservations(1);
            try (var harness = new Harness(ledger)) {
                var message = message(true, 1);
                var failure = unavailable();
                stubAdmission(harness, admission, call -> {
                    switch (uncertainty) {
                        case PRESENT -> call.callRealMethod();
                        case READ_FAILURE -> doThrow(unavailable()).when(harness.pending).find(source(message));
                        case FAILED_STORE -> doReturn(new OutboundStage.Status("memory", false,
                                OutboundStage.State.FAILED)).when(harness.pending).status();
                        case FAILED_DURING_READ -> doAnswer(read -> {
                            doReturn(new OutboundStage.Status("memory", false, OutboundStage.State.FAILED))
                                    .when(harness.pending).status();
                            return Optional.empty();
                        }).when(harness.pending).find(source(message));
                    }
                    throw failure;
                });
                assertThatThrownBy(() -> admission.accept(harness, message)).isSameAs(failure);
                assertThat(ledger.usedCapacity()).as(uncertainty.name()).isEqualTo(1);
                assertCapacityRejection(() -> ledger.reserveAdmission(new SourceId(UUID.randomUUID()), message));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Admission.class)
    void filePublicationFailureRetainsReservation(Admission admission) {
        var ledger = new MemoryDlrReservations(1);
        var pending = spy(new FilePendingMessageStore(root, 16));
        var router = new MemorySelectedRouterStore<StandardMessage>(pending, 16);
        var routed = new MemoryRoutedWorkStore<StandardMessage>(16, MemoryDlrAdmissionTest::copy);
        try (var coordinator = new DefaultOutboundCoordinator<>(pending, router, routed, 16, ledger)) {
            coordinator.start();
            var message = message(true, 1);
            var failure = unavailable();
            stubAdmission(pending, admission, call -> {
                call.callRealMethod();
                throw failure;
            });
            assertThatThrownBy(() -> admission.accept(coordinator, message)).isSameAs(failure);
            assertThat(pending.find(source(message))).isPresent();
            assertThat(ledger.usedCapacity()).isEqualTo(1);
            assertCapacityRejection(() -> ledger.reserveAdmission(new SourceId(UUID.randomUUID()), message));
        }
        try (var reopened = new FilePendingMessageStore(root, 16)) {
            reopened.open();
            assertThat(reopened.sourcesAfter(null, 16)).hasSize(1);
        }
    }

    @ParameterizedTest
    @EnumSource(Admission.class)
    void nonDlrAdmissionIgnoresDlrFullness(Admission admission) {
        var ledger = new MemoryDlrReservations(1);
        try (var harness = new Harness(ledger)) {
            admission.accept(harness, message(true, 1));
            var ordinary = message(false, 2);
            admission.accept(harness, ordinary);
            assertThat(harness.pending.find(source(ordinary))).isPresent();
            assertThat(ledger.usedCapacity()).isEqualTo(1);
        }
    }

    private static boolean acceptAfter(CountDownLatch start, Harness harness, Admission admission,
                                       StandardMessage message) throws Exception {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            admission.accept(harness, message);
            return true;
        } catch (OutboundStorageException failure) {
            assertThat(failure.reason()).isEqualTo(CAPACITY_EXCEEDED);
            return false;
        }
    }

    private static void assertCapacityRejection(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(OutboundStorageException.class,
                failure -> assertThat(failure.reason()).isEqualTo(CAPACITY_EXCEEDED));
    }

    private static OutboundStorageException unavailable() {
        return new OutboundStorageException(OutboundStage.Role.PENDING, UNAVAILABLE,
                "testAdmission|error|reason:injectedFailure");
    }

    private static void stubAdmission(Harness harness, Admission admission, Answer<?> answer) {
        stubAdmission(harness.pending, admission, answer);
    }

    private static void stubAdmission(PendingMessageStore<StandardMessage> pending, Admission admission, Answer<?> answer) {
        switch (admission) {
            case READY -> doAnswer(answer).when(pending).admit(any(), any());
            case HELD -> doAnswer(answer).when(pending).admitHeld(any(), any());
            case PART -> doAnswer(answer).when(pending).admitPart(any(), any(), any());
        }
    }

    private enum Admission {
        READY, HELD, PART;

        void accept(Harness harness, StandardMessage message) {
            accept(harness.coordinator, message);
        }

        void accept(DefaultOutboundCoordinator<StandardMessage> coordinator, StandardMessage message) {
            switch (this) {
                case READY -> coordinator.accept(source(message), message);
                case HELD -> coordinator.acceptHeld(source(message), message);
                case PART -> coordinator.acceptPart(source(message), message, Duration.ofMinutes(1));
            }
        }
    }

    private enum Uncertainty {
        PRESENT, READ_FAILURE, FAILED_STORE, FAILED_DURING_READ
    }

    private static final class Harness implements AutoCloseable {
        private final MemoryPendingMessageStore<StandardMessage> pending =
                spy(new MemoryPendingMessageStore<>(16, MemoryDlrAdmissionTest::copy));
        private final DefaultOutboundCoordinator<StandardMessage> coordinator;

        private Harness(MemoryDlrReservations reservations) {
            var router = new MemorySelectedRouterStore<>(pending, 16);
            var routed = new MemoryRoutedWorkStore<>(16, MemoryDlrAdmissionTest::copy);
            coordinator = new DefaultOutboundCoordinator<>(pending, router, routed, 16, reservations);
            coordinator.start();
        }

        @Override
        public void close() {
            coordinator.close();
        }
    }

    private static StandardMessage copy(StandardMessage message) {
        return PendingMessageData.capture(message).restore();
    }

    private static SourceId source(StandardMessage message) {
        return new SourceId(UUID.fromString(message.serial));
    }

    private static StandardMessage message(boolean dlr, int ordinal) {
        var message = new StandardMessage();
        message.serial = UUID.randomUUID().toString();
        message.body = "part";
        message.owner_id = "account";
        message.systemId = "system";
        message.from = "sender";
        message.to = "recipient";
        message.binheader = "0500037F03%02X".formatted(ordinal);
        if (dlr) {
            message.dlrReturnMetadata = DlrReturnMetadata.smpp("account", "system", "sender", "recipient");
        }
        return message;
    }
}
