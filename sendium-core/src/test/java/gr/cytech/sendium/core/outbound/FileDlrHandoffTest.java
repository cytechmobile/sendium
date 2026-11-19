package gr.cytech.sendium.core.outbound;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.file.FilePendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemoryRoutedWorkStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import gr.cytech.sendium.core.worker.FileDlrHandoffFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pending SMS ownership against the real file-DLR provider handoff. */
@Timeout(60)
class FileDlrHandoffTest {
    private static final SourceId SOURCE = new SourceId(UUID.fromString("b2000000-0000-0000-0000-000000000002"));
    private static final String GATEWAY = SOURCE.value().toString();
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {"accepted", "rejected"})
    void publicationFailureRetainsPendingSms(String outcome) throws Exception {
        try (var outbound = new Outbound(root)) {
            var work = outbound.dispatch();
            try (var dlr = FileDlrHandoffFixture.open(root.resolve("dlr"))) {
                dlr.failPublication();
                var completion = outbound.coordinator.complete(work.id(), handoff(dlr, outcome)).toCompletableFuture();
                assertThatThrownBy(() -> completion.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
                assertThat(outbound.pending.find(SOURCE)).isPresent();
            }
            try (var dlr = FileDlrHandoffFixture.open(root.resolve("dlr"))) {
                // Nothing from the failed publication is visible; a retried handoff then permits cleanup.
                assertThat(dlr.deliveryStatus(GATEWAY)).isEmpty();
                outbound.coordinator.complete(work.id(), handoff(dlr, outcome)).toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
                assertThat(outbound.pending.find(SOURCE)).isEmpty();
                assertThat(dlr.deliveryStatus(GATEWAY))
                        .contains(outcome.equals("accepted") ? "WAITING_PROVIDER" : "PENDING");
            }
        }
    }

    @Test
    void handoffCleanupInterruptionPreservesDocumentedBoundary() throws Exception {
        crashAfterHandoff();
        try (var outbound = new Outbound(root); var dlr = FileDlrHandoffFixture.open(root.resolve("dlr"))) {
            // The durable handoff survived, and so did SMS ownership: cleanup never ran.
            assertThat(dlr.deliveryStatus(GATEWAY)).contains("WAITING_PROVIDER");
            assertThat(dlr.correlationOwner("X1")).contains(GATEWAY);
            outbound.coordinator.start();
            assertThat(outbound.pending.find(SOURCE)).isPresent();

            // Recovery may submit to the provider again: the documented ambiguity, not exactly-once delivery.
            outbound.coordinator.selectToRouter(1);
            var selected = outbound.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(selected.sources()).containsExactly(SOURCE);
            outbound.coordinator.recordToRouted(selected, new Destination<>("provider", selected.message()));
            var work = outbound.coordinator.takeFromRouted("provider", Duration.ZERO).orElseThrow();
            Files.writeString(root.resolve("provider-effects"), "submitted\n", StandardOpenOption.APPEND);
            assertThat(Files.readAllLines(root.resolve("provider-effects"))).containsExactly("submitted", "submitted");

            // Repeating the same acceptance handoff is idempotent for the retained DLR lifecycle.
            outbound.coordinator.complete(work.id(), dlr.accepted(GATEWAY, "X1")).toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            assertThat(outbound.pending.find(SOURCE)).isEmpty();
            assertThat(dlr.deliveryStatus(GATEWAY)).contains("WAITING_PROVIDER");
            assertThat(dlr.correlationOwner("X1")).contains(GATEWAY);
        }
    }

    private static CompletionStage<Void> handoff(FileDlrHandoffFixture dlr, String outcome) {
        return outcome.equals("accepted") ? dlr.accepted(GATEWAY, "X1") : dlr.rejected(GATEWAY, "X1");
    }

    private void crashAfterHandoff() throws Exception {
        Path executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path arguments = root.resolve("java.args");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Files.writeString(arguments, "-cp\n\"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n");
        Path log = root.resolve("child.log");
        var child = new ProcessBuilder(executable.toString(), "@" + arguments, HandoffWriter.class.getName(),
                root.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!Files.exists(root.resolve("ready")) && child.isAlive() && System.nanoTime() < deadline) {
                child.waitFor(20, TimeUnit.MILLISECONDS);
            }
            assertThat(Files.exists(root.resolve("ready"))).withFailMessage("Child did not hand off:%n%s",
                    Files.readString(log)).isTrue();
            assertThat(child.isAlive()).isTrue();
        } finally {
            child.destroyForcibly();
            assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    // Commits the DLR handoff, then dies before the coordinator's source cleanup.
    public static final class HandoffWriter {
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]);
            var outbound = new Outbound(root);
            outbound.dispatch();
            Files.writeString(root.resolve("provider-effects"), "submitted\n");
            var dlr = FileDlrHandoffFixture.open(root.resolve("dlr"));
            dlr.accepted(GATEWAY, "X1").toCompletableFuture().get(10, TimeUnit.SECONDS);
            Files.writeString(root.resolve("ready"), "handoff");
            new CountDownLatch(1).await();
        }
    }

    private static final class Outbound implements AutoCloseable {
        private final FilePendingMessageStore pending;
        private final DefaultOutboundCoordinator<StandardMessage> coordinator;

        private Outbound(Path root) {
            pending = new FilePendingMessageStore(root.resolve("spool"), 10);
            coordinator = new DefaultOutboundCoordinator<>(pending, new MemorySelectedRouterStore<>(pending, 2),
                    new MemoryRoutedWorkStore<>(10, message -> PendingMessageData.capture(message).restore()), 2);
        }

        // Accepts the SMS and takes it for provider submission.
        private OutboundWork.Routed<StandardMessage> dispatch() throws InterruptedException {
            coordinator.start();
            var message = new StandardMessage();
            message.serial = GATEWAY;
            message.body = "pending sms";
            coordinator.accept(SOURCE, message);
            coordinator.selectToRouter(1);
            var selected = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            coordinator.recordToRouted(selected, new Destination<>("provider", selected.message()));
            return coordinator.takeFromRouted("provider", Duration.ZERO).orElseThrow();
        }

        @Override
        public void close() {
            coordinator.close();
        }
    }
}
