package gr.cytech.sendium.core.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static gr.cytech.sendium.core.worker.FileDlrTestSupport.httpState;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.pending;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.smppState;
import static org.assertj.core.api.Assertions.assertThat;

class FileDlrConcurrencyTest {
    @TempDir
    Path root;

    @Test
    @Timeout(20)
    void concurrentClaimersExposeOneLiveOwner() throws Exception {
        try (var storage = FileDlrStorage.open(root);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            MessageState smpp = pending(storage, smppState("A"));
            List<String> due = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                due.add(pending(storage, httpState()).getGatewayMsgId());
            }
            var start = new CountDownLatch(1);
            List<Future<Optional<MessageState>>> starters = new ArrayList<>();
            List<Future<List<MessageState>>> claimers = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                starters.add(executor.submit(() -> {
                    start.await();
                    return storage.startDeliveryAttempt(smpp.getGatewayMsgId(), MessageState.DeliveryChannel.SMPP);
                }));
                claimers.add(executor.submit(() -> {
                    start.await();
                    return storage.claimDueHttpDeliveries(3);
                }));
            }
            start.countDown();

            int owners = 0;
            for (var starter : starters) {
                owners += starter.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0;
            }
            assertThat(owners).isOne();
            List<String> claimed = new ArrayList<>();
            for (var claimer : claimers) {
                claimer.get(10, TimeUnit.SECONDS).forEach(state -> claimed.add(state.getGatewayMsgId()));
            }
            assertThat(new HashSet<>(claimed)).hasSameSizeAs(claimed).containsExactlyInAnyOrderElementsOf(due);
        }
    }
}
