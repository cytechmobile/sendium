package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.DlrReturnMetadata;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.CAPACITY_EXCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(20)
class MemoryDlrReservationsTest {
    @Test
    void sameSourceRetryReusesEntry() {
        var reservations = new MemoryDlrReservations(1);
        var source = new SourceId(UUID.randomUUID());
        var first = reservations.reserveAdmission(source, message());
        var retry = reservations.reserveAdmission(source, message());
        assertThat(reservations.usedCapacity()).isEqualTo(1);
        assertThat(reservations.maxCapacity()).isEqualTo(1);
        retry.rollback();
        assertThat(reservations.usedCapacity()).isEqualTo(1);
        first.rollback();
        first.rollback();
        assertThat(reservations.usedCapacity()).isZero();
    }

    @Test
    void oldRollbackCannotRemoveReacquiredEntry() {
        var reservations = new MemoryDlrReservations(1);
        var source = new SourceId(UUID.randomUUID());
        var old = reservations.reserveAdmission(source, message());
        old.rollback();
        reservations.reserveAdmission(source, message());
        old.rollback();
        assertThat(reservations.usedCapacity()).isEqualTo(1);
    }

    @Test
    void concurrentReservationsCannotExceedMaximum() throws Exception {
        var reservations = new MemoryDlrReservations(1);
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> reserveAfter(start, reservations));
            var second = threads.submit(() -> reserveAfter(start, reservations));
            start.countDown();
            assertThat((first.get(5, TimeUnit.SECONDS) ? 1 : 0) +
                    (second.get(5, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
        }
        assertThat(reservations.usedCapacity()).isEqualTo(1);
    }

    @Test
    void nonDlrDoesNotAllocateOrReleaseAnEntry() {
        var reservations = new MemoryDlrReservations(1);
        var source = new SourceId(UUID.randomUUID());
        reservations.reserveAdmission(source, message());
        reservations.reserveAdmission(source, new StandardMessage()).rollback();
        reservations.reserveAdmission(new SourceId(UUID.randomUUID()), new StandardMessage()).rollback();
        assertThat(reservations.usedCapacity()).isEqualTo(1);
    }

    @Test
    void invalidCapacityIsRejected() {
        assertThatThrownBy(() -> new MemoryDlrReservations(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryDlrReservations(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static boolean reserveAfter(CountDownLatch start, MemoryDlrReservations reservations) throws Exception {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            reservations.reserveAdmission(new SourceId(UUID.randomUUID()), message());
            return true;
        } catch (OutboundStorageException failure) {
            assertThat(failure.reason()).isEqualTo(CAPACITY_EXCEEDED);
            return false;
        }
    }

    private static StandardMessage message() {
        var message = new StandardMessage();
        message.dlrReturnMetadata = DlrReturnMetadata.smpp("account", "system", "sender", "recipient");
        return message;
    }
}
