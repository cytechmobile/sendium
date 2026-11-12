package gr.cytech.sendium.core.smpp.server;

import gr.cytech.sendium.core.message.StandardMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DlrDeliveryBatchTest {
    @Mock private SmppServerMessageStore<StandardMessage> store;

    private StandardMessage message;
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    @AfterEach
    void closeMetrics() {
        metrics.close();
    }

    @BeforeEach
    void setUp() {
        message = new StandardMessage();
        message.serial = "gateway-1";
        message.type = StandardMessage.MSG_DLR;
    }

    @Test
    void singlePartSuccessCompletesAttempt() {
        when(store.completeDlrDeliveryAttempt(message, 3)).thenReturn(true);
        DlrDeliveryBatch<StandardMessage> batch = batch(3, Set.of(0));

        batch.partSucceeded(0);

        verify(store).completeDlrDeliveryAttempt(message, 3);
        verify(store, never()).releaseDlrDeliveryAttempt(
                message, 3, DlrDeliveryBatch.COMPLETION_STORAGE_ERROR);
        assertThat(batch.isActive()).isFalse();
        assertThat(returns("success")).isEqualTo(1);
        assertThat(returns("failure")).isZero();
    }

    @Test
    void multipartCompletesAfterAllDistinctPartsAndIgnoresDuplicateResponse() {
        when(store.completeDlrDeliveryAttempt(message, 4)).thenReturn(true);
        DlrDeliveryBatch<StandardMessage> batch = batch(4, Set.of(0, 1, 2));

        batch.partSucceeded(0);
        batch.partSucceeded(0);
        batch.partSucceeded(2);
        verify(store, never()).completeDlrDeliveryAttempt(message, 4);
        assertThat(returns("success")).isZero();
        batch.partSucceeded(1);

        verify(store, times(1)).completeDlrDeliveryAttempt(message, 4);
        assertThat(returns("success")).isEqualTo(1);
    }

    @Test
    void firstFailureReleasesOnlyOnce() {
        when(store.releaseDlrDeliveryAttempt(message, 5, "timeout")).thenReturn(true);
        DlrDeliveryBatch<StandardMessage> batch = batch(5, Set.of(0, 1));

        batch.fail("timeout");
        batch.fail("non_ok_response");
        batch.partSucceeded(0);

        verify(store, times(1)).releaseDlrDeliveryAttempt(message, 5, "timeout");
        verify(store, never()).completeDlrDeliveryAttempt(message, 5);
        assertThat(returns("failure")).isEqualTo(1);
        assertThat(returns("success")).isZero();
    }

    @Test
    void completionStorageFailureReleasesAttempt() {
        when(store.completeDlrDeliveryAttempt(message, 6)).thenReturn(false);
        when(store.releaseDlrDeliveryAttempt(message, 6, DlrDeliveryBatch.COMPLETION_STORAGE_ERROR))
                .thenReturn(true);
        DlrDeliveryBatch<StandardMessage> batch = batch(6, Set.of(0));

        batch.partSucceeded(0);

        verify(store).releaseDlrDeliveryAttempt(message, 6, DlrDeliveryBatch.COMPLETION_STORAGE_ERROR);
        assertThat(returns("success")).isEqualTo(1);
        assertThat(returns("failure")).isZero();
    }

    @Test
    void completionStorageExceptionReleasesAttempt() {
        when(store.completeDlrDeliveryAttempt(message, 9)).thenThrow(new IllegalStateException("database down"));
        when(store.releaseDlrDeliveryAttempt(message, 9, DlrDeliveryBatch.COMPLETION_STORAGE_ERROR))
                .thenReturn(true);
        DlrDeliveryBatch<StandardMessage> batch = batch(9, Set.of(0));

        batch.partSucceeded(0);

        verify(store).releaseDlrDeliveryAttempt(message, 9, DlrDeliveryBatch.COMPLETION_STORAGE_ERROR);
    }

    @Test
    void callbackFromTerminalOldAttemptCannotAffectNewAttempt() {
        when(store.releaseDlrDeliveryAttempt(message, 7, "timeout")).thenReturn(true);
        when(store.completeDlrDeliveryAttempt(message, 8)).thenReturn(true);
        DlrDeliveryBatch<StandardMessage> oldBatch = batch(7, Set.of(0));
        DlrDeliveryBatch<StandardMessage> newBatch = batch(8, Set.of(0));

        oldBatch.fail("timeout");
        newBatch.partSucceeded(0);
        oldBatch.partSucceeded(0);

        verify(store, never()).completeDlrDeliveryAttempt(message, 7);
        verify(store).completeDlrDeliveryAttempt(message, 8);
    }

    private DlrDeliveryBatch<StandardMessage> batch(int attempt, Set<Integer> expectedParts) {
        var batch = new DlrDeliveryBatch<>(message, attempt, expectedParts, store, null, metrics);
        assertThat(batch.beginSend()).isTrue();
        return batch;
    }

    @Test
    void failureBeforeAnySendIsNotAReturnAttempt() {
        var batch = new DlrDeliveryBatch<>(message, 10, Set.of(0), store, null, metrics);
        when(store.releaseDlrDeliveryAttempt(message, 10, "enqueue_failed")).thenReturn(true);
        batch.fail("enqueue_failed");
        assertThat(batch.beginSend()).isFalse();
        assertThat(returns("success")).isZero();
        assertThat(returns("failure")).isZero();
    }

    private double returns(String result) {
        return metrics.get("sendium.dlr.return.attempts").tag("channel", "smpp").tag("result", result).counter().count();
    }
}
