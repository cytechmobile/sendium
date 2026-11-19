package gr.cytech.sendium.core.worker;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static gr.cytech.sendium.core.worker.FileDlrTestSupport.httpState;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.raw;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.single;
import static org.assertj.core.api.Assertions.assertThat;

/** Forced child death on either side of each durable publication, with no graceful close. */
@Timeout(60)
class FileDlrForcedRestartTest {
    @TempDir Path root;

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "acceptance, before", "acceptance, after",
        "terminal, before", "terminal, after",
        "claim, before", "claim, after",
        "retry, before", "retry, after",
        "completion, before", "completion, after"})
    void committedBoundariesSurviveForcedDeath(String operation, String boundary) throws Exception {
        crash(operation, boundary);
        String id = Files.readString(root.resolve("id"));
        // "after" means the operation returned successfully; "before" means its commit had not started.
        boolean applied = boundary.equals("after");
        try (var storage = FileDlrStorage.open(root)) {
            var state = storage.getState(id);
            switch (operation) {
                case "acceptance" -> {
                    assertThat(state.isPresent()).isEqualTo(applied);
                    assertThat(storage.correlationOwner("P", "X").isPresent()).isEqualTo(applied);
                }
                case "terminal" -> {
                    assertThat(state.orElseThrow().getDeliveryStatus()).isEqualTo(applied ?
                        MessageState.DeliveryStatus.PENDING : MessageState.DeliveryStatus.WAITING_PROVIDER);
                    assertThat(storage.correlationOwner("P", "X").isPresent()).isEqualTo(!applied);
                }
                case "claim" -> {
                    assertThat(state.orElseThrow().getDeliveryAttemptCount()).isEqualTo(applied ? 1 : 0);
                    // A committed claim's lease survives; an uncommitted one never existed.
                    assertThat(storage.startDeliveryAttempt(id, MessageState.DeliveryChannel.HTTP).isPresent())
                            .isEqualTo(!applied);
                }
                case "retry" -> {
                    assertThat(state.orElseThrow().getLastDeliveryResult()).isEqualTo(applied ? "timeout" : null);
                    assertThat(state.orElseThrow().getDeliveryAttemptCount()).isEqualTo(1);
                }
                case "completion" -> assertThat(state.isPresent()).isEqualTo(!applied);
                default -> throw new IllegalArgumentException(operation);
            }
        }
        try (Connection connection = raw(root)) {
            assertThat(single(connection, "PRAGMA integrity_check")).isEqualTo("ok");
            // No mixed generation: correlations exist only for receipts still waiting for their provider.
            assertThat(single(connection, """
                    SELECT count(*) FROM provider_correlation c JOIN dlr_message m USING (gateway_message_id)
                    WHERE m.delivery_status <> 'WAITING_PROVIDER'
                    """)).isEqualTo("0");
            assertThat(single(connection, """
                    SELECT count(*) FROM dlr_message WHERE delivery_status = 'WAITING_PROVIDER'
                      AND NOT EXISTS (SELECT 1 FROM provider_correlation c
                                      WHERE c.gateway_message_id = dlr_message.gateway_message_id)
                    """)).isEqualTo("0");
        }
        assertThat(root.resolve(FileDlrStorage.DATABASE_FILE + "-journal")).doesNotExist();
    }

    private void crash(String operation, String boundary) throws Exception {
        Path executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path arguments = root.resolve("java.args");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Files.writeString(arguments, "-cp\n\"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n");
        Path log = root.resolve("child.log");
        var child = new ProcessBuilder(executable.toString(), "@" + arguments, CrashWriter.class.getName(),
                root.toString(), operation, boundary).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!Files.exists(root.resolve("ready")) && child.isAlive() && System.nanoTime() < deadline) {
                child.waitFor(20, TimeUnit.MILLISECONDS);
            }
            assertThat(Files.exists(root.resolve("ready"))).withFailMessage("Child did not reach %s/%s:%n%s",
                    operation, boundary, Files.readString(log)).isTrue();
            assertThat(child.isAlive()).isTrue();
        } finally {
            child.destroyForcibly();
            assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    public static final class CrashWriter {
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]);
            String operation = args[1];
            var storage = FileDlrStorage.open(root);
            MessageState state = httpState();
            String id = state.getGatewayMsgId();
            Files.writeString(root.resolve("id"), id);
            int prepared = switch (operation) {
                case "acceptance" -> 0;
                case "terminal" -> 1;
                case "claim" -> 2;
                default -> 3;
            };
            if (prepared >= 1) {
                storage.recordProviderAccepted(state, "P", "X");
            }
            if (prepared >= 2) {
                storage.resolveDlr("P", "X", MessageState.MessageStatus.DELIVERED, 1, "0").orElseThrow();
            }
            if (prepared >= 3) {
                storage.claimDueHttpDeliveries(1).getFirst();
            }
            Path ready = root.resolve("ready");
            if (args[2].equals("before")) {
                // Hold the transaction open with all changes staged; death must roll it back.
                storage.beforeCommit = c -> {
                    signal(ready);
                    awaitDeath();
                };
            }
            switch (operation) {
                case "acceptance" -> storage.recordProviderAccepted(state, "P", "X");
                case "terminal" -> storage.resolveDlr("P", "X", MessageState.MessageStatus.DELIVERED, 1, "0");
                case "claim" -> storage.claimDueHttpDeliveries(1);
                case "retry" -> storage.retryDelivery(id, 1, "timeout", Long.MAX_VALUE / 2);
                case "completion" -> storage.completeDelivery(id, 1);
                default -> throw new IllegalArgumentException(operation);
            }
            signal(ready);
            awaitDeath();
        }

        private static void signal(Path ready) {
            try {
                Files.writeString(ready, "ready");
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private static void awaitDeath() {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
