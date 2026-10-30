import gr.cytech.sendium.performance.MessageLedger;
import gr.cytech.sendium.performance.ProviderSettings;
import gr.cytech.sendium.performance.RunConfig;
import gr.cytech.sendium.performance.SmppProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Local startup probe using the production simulator; deliberately generates no traffic. */
public final class KannelBindProbe {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of("/probe");
        String password = Files.readString(directory.resolve("provider-password")).strip();
        RunConfig workload = new RunConfig(1, "http", 30, 0, 0, 1, 1,
                true, 5, 1000, 5000, 1000, 100_000);
        MessageLedger ledger = new MessageLedger(workload, "kannel-bind", System::nanoTime,
                TimeUnit.SECONDS.toNanos(120));
        ProviderSettings settings = new ProviderSettings("0.0.0.0", 27779,
                "provider", password, 1, 1000, 100_000, 5000);
        try (SmppProvider provider = new SmppProvider(workload, settings, ledger)) {
            provider.start();
            Files.writeString(directory.resolve("provider-listening"), "ready\n");
            long bindDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (!provider.awaitBound(Duration.ofMillis(100))) {
                if (Files.exists(directory.resolve("stop-provider"))) {
                    return;
                }
                if (System.nanoTime() >= bindDeadline) {
                    throw new IllegalStateException("Kannel did not complete an authenticated transceiver bind");
                }
            }
            Files.writeString(directory.resolve("provider-bound"), "bound\n");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
            while (!Files.exists(directory.resolve("stop-provider"))) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("Local probe exceeded its lifetime");
                }
                if (!provider.awaitBound(Duration.ofMillis(100))) {
                    throw new IllegalStateException("Kannel provider session disconnected before shutdown");
                }
                if (provider.snapshot().problems().values().stream().anyMatch(value -> value != 0)) {
                    throw new IllegalStateException("Unexpected provider problem during bind-only probe");
                }
                Thread.sleep(100);
            }
            if (provider.snapshot().problems().values().stream().anyMatch(value -> value != 0)) {
                throw new IllegalStateException("Provider problem at probe shutdown");
            }
        }
    }
}
