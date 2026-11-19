package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static gr.cytech.sendium.core.worker.FileDlrTestSupport.httpState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileDlrBatchTest {
    @TempDir
    Path root;

    @Test
    void orderedBatchPreservesOwnershipAndTerminalRules() {
        MessageState g = httpState();
        MessageState h = httpState();
        MessageState other = httpState();
        try (var storage = FileDlrStorage.open(root)) {
            var results = storage.applyProviderBatch(List.of(
                    new ProviderDlrEvent.Accepted(g, "P", "X"),
                    new ProviderDlrEvent.Accepted(h, "P", "X"),
                    receipt("X"),
                    new ProviderDlrEvent.Accepted(h, "P", "late"),
                    new ProviderDlrEvent.Accepted(other, "P", "other")));

            assertThat(results).hasSize(5);
            assertThat(results.get(2).state()).get().extracting(MessageState::getGatewayMsgId)
                    .isEqualTo(h.getGatewayMsgId());
            assertThat(results.get(3).failure()).isNotNull();
            assertThat(results.get(4).failure()).isNull();
        }
        try (var reopened = FileDlrStorage.open(root)) {
            assertThat(reopened.getState(g.getGatewayMsgId()).orElseThrow().getProviderMessageId()).isNull();
            assertThat(reopened.getState(h.getGatewayMsgId()).orElseThrow().getDeliveryStatus())
                    .isEqualTo(MessageState.DeliveryStatus.PENDING);
            assertThat(reopened.correlationOwner("P", "late")).isEmpty();
            assertThat(reopened.correlationOwner("P", "other")).contains(other.getGatewayMsgId());
        }
    }

    @Test
    @Timeout(20)
    void failedPublicationCannotCompleteSuccessfulFutures() throws Exception {
        MessageState g = httpState();
        MessageState h = httpState();
        var metrics = new SimpleMeterRegistry();
        var storage = FileDlrStorage.open(root);
        // Every event is applied, then the commit fails with an uncertain outcome.
        storage.beforeCommit = c -> c.close();
        var managed = new ManagedDlrStorage();
        managed.meterRegistry = metrics;
        Field delegate = ManagedDlrStorage.class.getDeclaredField("delegate");
        delegate.setAccessible(true);
        delegate.set(managed, storage);
        var processor = new ProviderDlrProcessor(managed, metrics, 3, Duration.ofSeconds(2), 10, Duration.ofSeconds(5));
        try {
            List<CompletableFuture<?>> futures = List.of(
                    processor.submit(new ProviderDlrEvent.Accepted(g, "P", "X")).toCompletableFuture(),
                    processor.submit(receipt("X")).toCompletableFuture(),
                    processor.submit(new ProviderDlrEvent.Accepted(h, "P", "Y")).toCompletableFuture());
            for (CompletableFuture<?> future : futures) {
                assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            }
        } finally {
            processor.close();
            storage.close();
            metrics.close();
        }
        try (var reopened = FileDlrStorage.open(root)) {
            assertThat(reopened.getState(g.getGatewayMsgId())).isEmpty();
            assertThat(reopened.getState(h.getGatewayMsgId())).isEmpty();
            assertThat(reopened.correlationOwner("P", "Y")).isEmpty();
        }
    }

    private static ProviderDlrEvent.Receipt receipt(String providerId) {
        return new ProviderDlrEvent.Receipt("P", providerId, MessageState.MessageStatus.DELIVERED,
                StandardMessage.DLR_STAT_DELIVRD, "0");
    }
}
