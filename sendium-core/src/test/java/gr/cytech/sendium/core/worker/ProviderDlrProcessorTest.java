package gr.cytech.sendium.core.worker;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderDlrProcessorTest {
    private final ManagedDlrStorage storage = mock(ManagedDlrStorage.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    @Test
    void rejectsInvalidBatchSettingsBeforeStartingTheWorker() {
        assertThatThrownBy(() -> processor(0, Duration.ofMillis(500), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor(1001, Duration.ofMillis(500), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor(100, Duration.ZERO, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor(100, Duration.ofMillis(500), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fullBatchPreservesOrderAndDoesNotCompleteBeforeCommitReturns() throws Exception {
        var first = accepted("first");
        var second = receipt("first");
        var third = accepted("third");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        when(storage.applyProviderBatch(List.of(first, second, third))).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(commit.await(5, TimeUnit.SECONDS)).isTrue();
            return successful(3);
        });
        try (var processor = processor(3, Duration.ofSeconds(30), 10)) {
            var a = processor.submit(first).toCompletableFuture();
            var b = processor.submit(second).toCompletableFuture();
            var c = processor.submit(third).toCompletableFuture();
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(a).isNotDone();
                assertThat(b).isNotDone();
                assertThat(c).isNotDone();
            } finally {
                commit.countDown();
            }
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
            c.get(5, TimeUnit.SECONDS);
            verify(storage).applyProviderBatch(List.of(first, second, third));
        }
    }

    @Test
    void partialBatchFlushesAtItsDeadline() throws Exception {
        when(storage.applyProviderBatch(anyList())).thenReturn(successful(1));
        try (var processor = processor(100, Duration.ofMillis(25), 10)) {
            assertThat(processor.submit(accepted("partial")).toCompletableFuture().get(5, TimeUnit.SECONDS)).isEmpty();
        }
    }

    @Test
    void failedBatchFailsEveryResultAndLaterBatchesStillRun() throws Exception {
        var failure = new DlrStorageException("rollback");
        when(storage.applyProviderBatch(anyList())).thenThrow(failure).thenReturn(successful(2));
        try (var processor = processor(2, Duration.ofSeconds(30), 10)) {
            var a = processor.submit(accepted("a")).toCompletableFuture();
            var b = processor.submit(receipt("a")).toCompletableFuture();
            assertThatThrownBy(() -> a.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCause(failure);
            assertThatThrownBy(() -> b.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCause(failure);
            var c = processor.submit(accepted("c")).toCompletableFuture();
            var d = processor.submit(receipt("c")).toCompletableFuture();
            c.get(5, TimeUnit.SECONDS);
            d.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void capacityIncludesInFlightWorkAndRejectsWithoutBlocking() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        when(storage.applyProviderBatch(anyList())).thenAnswer(invocation -> {
            entered.countDown();
            commit.await(5, TimeUnit.SECONDS);
            return successful(1);
        });
        try (var processor = processor(1, Duration.ofSeconds(30), 2)) {
            var first = processor.submit(accepted("first")).toCompletableFuture();
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var second = processor.submit(receipt("first")).toCompletableFuture();
                var rejected = processor.submit(accepted("excess")).toCompletableFuture();
                assertThat(rejected).isCompletedExceptionally();
                assertThat(second).isNotDone();
                assertThat(metrics.get("sendium.dlr.batch.rejected").counter().count()).isEqualTo(1);
            } finally {
                commit.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void shutdownFlushesPartialBatchAndRejectsNewWork() throws Exception {
        when(storage.applyProviderBatch(anyList())).thenAnswer(invocation -> successful(invocation.<List<?>>getArgument(0).size()));
        var processor = processor(100, Duration.ofSeconds(30), 10);
        var first = processor.submit(accepted("first")).toCompletableFuture();
        var second = processor.submit(receipt("first")).toCompletableFuture();
        processor.close();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        assertThat(processor.submit(accepted("stopped")).toCompletableFuture()).isCompletedExceptionally();
    }

    @Test
    void shutdownTimeoutNeverTurnsALateCommitIntoSuccess() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        when(storage.applyProviderBatch(anyList())).thenAnswer(invocation -> {
            entered.countDown();
            boolean done = false;
            while (!done) {
                try {
                    done = release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // Simulate a JDBC call that cannot be interrupted while waiting for the server.
                }
            }
            returned.countDown();
            return successful(1);
        });
        var processor = new ProviderDlrProcessor(storage, metrics, 1, Duration.ofMillis(10), 2, Duration.ofMillis(100));
        var result = processor.submit(accepted("blocked")).toCompletableFuture();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            processor.close();
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(DlrStorageException.class);
        } finally {
            release.countDown();
            processor.close();
        }
        assertThat(returned.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(result).isCompletedExceptionally();
    }

    @Test
    void completionIoDoesNotBlockTheNextDatabaseBatch() throws Exception {
        AtomicInteger batches = new AtomicInteger();
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        when(storage.applyProviderBatch(anyList())).thenAnswer(invocation -> {
            if (batches.incrementAndGet() == 1) {
                allowFirstCommit.await(5, TimeUnit.SECONDS);
            }
            return successful(1);
        });
        CountDownLatch completing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var processor = processor(1, Duration.ofSeconds(30), 10)) {
            CompletionStage<?> first = processor.submit(accepted("first")).thenRun(() -> {
                completing.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            allowFirstCommit.countDown();
            try {
                assertThat(completing.await(5, TimeUnit.SECONDS)).isTrue();
                processor.submit(accepted("second")).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertThat(batches).hasValue(2);
            } finally {
                release.countDown();
            }
            first.toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private ProviderDlrProcessor processor(int size, Duration delay, int capacity) {
        return new ProviderDlrProcessor(storage, metrics, size, delay, capacity, Duration.ofSeconds(5));
    }

    private ProviderDlrEvent.Accepted accepted(String id) {
        var message = new MessageState(UUID.randomUUID().toString(), "account", "system", "source", "destination", null);
        message.setDeliveryChannel(MessageState.DeliveryChannel.SMPP);
        return new ProviderDlrEvent.Accepted(message, "provider", id);
    }

    private ProviderDlrEvent.Receipt receipt(String id) {
        return new ProviderDlrEvent.Receipt("provider", id, MessageState.MessageStatus.DELIVERED, 0, "000");
    }

    private List<ProviderDlrEvent.Result> successful(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(ignored -> ProviderDlrEvent.Result.success(Optional.empty())).toList();
    }
}
