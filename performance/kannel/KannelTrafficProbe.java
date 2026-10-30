import com.fasterxml.jackson.databind.ObjectMapper;
import gr.cytech.sendium.performance.HttpAdmissionClient;
import gr.cytech.sendium.performance.HttpGatewayProtocol;
import gr.cytech.sendium.performance.HttpIngressSettings;
import gr.cytech.sendium.performance.HttpReceiptReceiver;
import gr.cytech.sendium.performance.MessageLedger;
import gr.cytech.sendium.performance.RunConfig;
import gr.cytech.sendium.performance.SmppProvider;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Bounded correctness check using the shared HTTP adapters, ledger, and SMPP simulator. */
public final class KannelTrafficProbe {
    private static final int MESSAGES = 10;

    public static void run(Path directory, RunConfig workload, MessageLedger ledger, SmppProvider provider)
            throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("scope", "single-part-http-dlr-correctness");
        result.put("requestedMessages", MESSAGES);
        result.put("status", "failed");
        try (HttpReceiptReceiver receiver = new HttpReceiptReceiver(new InetSocketAddress("0.0.0.0", 8081),
                "/dlr", 2, 1000, true, ledger, HttpGatewayProtocol.KANNEL)) {
            receiver.start();
            HttpIngressSettings settings = new HttpIngressSettings(URI.create("http://gateway:8080/sendsms"),
                    "smoke", Files.readString(directory.resolve("http-password")).strip(),
                    "306900000001", "306900000002", URI.create("http://provider:8081/dlr"));
            try (HttpAdmissionClient client = new HttpAdmissionClient(workload, settings, ledger,
                    HttpGatewayProtocol.KANNEL)) {
                try {
                    for (int i = 0; i < MESSAGES; i++) {
                        var outcome = client.submit(true).orElseThrow().completion().get(10, TimeUnit.SECONDS);
                        if (outcome != MessageLedger.Admission.ACCEPTED) {
                            throw new IllegalStateException("HTTP admission did not succeed");
                        }
                        Thread.sleep(33);
                    }
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (ledger.hasOutstandingWork() || provider.snapshot().pending() != 0
                            || client.pendingRequests() != 0 || receiver.pendingAcknowledgements() != 0) {
                        if (System.nanoTime() >= deadline) {
                            throw new IllegalStateException("Message/DLR drain did not finish");
                        }
                        Thread.sleep(10);
                    }
                    var counts = ledger.snapshot();
                    boolean complete = counts.attempted() == MESSAGES && counts.accepted() == MESSAGES
                            && counts.providerArrivals() == MESSAGES && counts.providerResponses() == MESSAGES
                            && counts.receiptsGenerated() == MESSAGES && counts.receiptsForwarded() == MESSAGES
                            && counts.acceptedProviderCompleted() == MESSAGES
                            && counts.acceptedReceiptForwarded() == MESSAGES
                            && counts.violations() == 0 && ledger.eventsBeyondRetention() == 0
                            && zero(client.problems()) && zero(receiver.problems())
                            && zero(provider.snapshot().problems());
                    result.put("status", complete ? "passed" : "failed");
                    result.put("issues", complete ? List.of() : List.of("INCONSISTENT_ACCOUNTING"));
                } catch (Exception exception) {
                    result.put("issues", List.of("TRAFFIC_PROBE_FAILED", exception.getClass().getSimpleName()));
                } finally {
                    ledger.freeze();
                    result.put("counts", ledger.snapshot());
                    result.put("admissionProblems", client.problems());
                    result.put("receiptProblems", receiver.problems());
                    result.put("providerProblems", provider.snapshot().problems());
                    result.put("pendingProviderWork", provider.snapshot().pending());
                    result.put("eventsBeyondRetention", ledger.eventsBeyondRetention());
                }
            }
        }
        Path temporary = directory.resolve("traffic-result.tmp");
        new ObjectMapper().writeValue(temporary.toFile(), result);
        Files.move(temporary, directory.resolve("traffic-result.json"), StandardCopyOption.ATOMIC_MOVE);
    }

    private static boolean zero(Map<?, Long> problems) {
        return problems.values().stream().allMatch(value -> value == 0);
    }
}
