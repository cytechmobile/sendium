package gr.cytech.sendium.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkCommandTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private ResultReport.Context context(String identity) {
        var application = mapper.createObjectNode(); application.putArray("jvmArguments"); application.putObject("connections"); application.put("dlrStorage", "postgresql");
        var infrastructure = mapper.createObjectNode(); infrastructure.put("preset", "command-fixture").put("region", "local");
        infrastructure.putObject("sendium"); infrastructure.putObject("harness"); infrastructure.putObject("database");
        return new ResultReport.Context(new ResultReport.Provenance("command-fixture", "a".repeat(40), "b".repeat(40), null, identity, List.of()),
                application, infrastructure, List.of("lifecycle.jsonl"));
    }
    private Path inputs() throws Exception {
        var deployment = mapper.readTree(Path.of("src/test/resources/deployment.json").toFile());
        var sockets = new java.util.ArrayList<java.net.ServerSocket>();
        try {
            for (String field : List.of("httpPort", "smppPort", "providerPort", "callbackPort")) {
                var socket = new java.net.ServerSocket(0); sockets.add(socket);
                ((com.fasterxml.jackson.databind.node.ObjectNode) deployment).put(field, socket.getLocalPort());
            }
        } finally { for (var socket : sockets) { socket.close(); } }
        // These cases stop before binds/readiness; no database or application is started.
        Path root = directory.resolve("generated");
        var generator = new BenchmarkConfigGenerator();
        generator.write(root, generator.generate(config(), mapper.treeToValue(deployment, BenchmarkDeployment.class)));
        return root.resolve("harness");
    }
    private RunConfig config() { return new RunConfig(1, "http", 1, 100, 0, 2, 0, false, 0, 0, 1000, 1, 10); }

    @Test
    void setupFailureProducesReportsAndClassifiedExitWithoutLeakingPrivateInputs() throws Exception {
        Path inputs = inputs();
        Path execution = directory.resolve("execution.json");
        mapper.writeValue(execution.toFile(), new ExecutionSettings(1, 1, 30, 256, context("different-identity"), null, null));
        Path output = directory.resolve("failed");
        assertEquals(1, HarnessMain.run(new String[]{"run", inputs.toString(), execution.toString(), output.toString()}));
        var report = mapper.readTree(output.resolve("results.json").toFile());
        assertEquals("failed", report.at("/validity/status").asText());
        assertTrue(report.at("/validity/reasons").toString().contains("SETUP_FAILED"));
        assertEquals("REPORTED", mapper.readTree(output.resolve("state.json").toFile()).path("phase").asText());
        String secret = mapper.readTree(inputs.resolve("runtime-settings.json").toFile()).at("/provider/password").asText();
        assertFalse(Files.readString(output.resolve("results.json")).contains(secret));
        String original = Files.readString(output.resolve("results.json"));
        assertEquals(2, HarnessMain.run(new String[]{"run", inputs.toString(), execution.toString(), output.toString()}));
        assertEquals(original, Files.readString(output.resolve("results.json")));
        Path resources = directory.resolve("resources.json");
        mapper.writeValue(resources.toFile(), ExecutionSettings.ResourceFiles.empty());
        Path finalized = directory.resolve("final-failure");
        assertEquals(1, HarnessMain.run(new String[]{"finalize-report", output.resolve("results.json").toString(), resources.toString(), finalized.toString()}));
        assertEquals(report.path("overall"), mapper.readTree(finalized.resolve("results.json").toFile()).path("overall"));
    }

    @Test
    void readinessTimeoutReportsFailureAndReleasesStartedListeners() throws Exception {
        Path inputs = inputs();
        int port = mapper.readTree(inputs.resolve("runtime-settings.json").toFile()).at("/provider/port").asInt();
        Path execution = directory.resolve("timeout-execution.json");
        mapper.writeValue(execution.toFile(), new ExecutionSettings(1, 1, 30, 256, context("local-test-1"), null, null));
        Path output = directory.resolve("timed-out");
        assertEquals(1, HarnessMain.run(new String[]{"run", inputs.toString(), execution.toString(), output.toString()}));
        var result = mapper.readTree(output.resolve("results.json").toFile());
        assertEquals(0, result.at("/overall/counts/attempted").asLong());
        assertTrue(result.at("/validity/reasons").toString().contains("SETUP_FAILED"));
        try (var socket = new java.net.ServerSocket(port)) { assertEquals(port, socket.getLocalPort()); }
    }

    @Test
    void offlineResourceFinalizationCanResolveMissingEvidenceWithoutChangingWorkloadData() throws Exception {
        var config = config(); var clock = new AtomicLong();
        var ledger = new MessageLedger(config, "finalize", clock::get, 0);
        ledger.beginMeasurement(0, TimeUnit.SECONDS.toNanos(2));
        ledger.admission(ledger.attempt(true), MessageLedger.Admission.REJECTED);
        clock.set(TimeUnit.SECONDS.toNanos(1)); ledger.admission(ledger.attempt(true), MessageLedger.Admission.REJECTED);
        clock.set(TimeUnit.SECONDS.toNanos(2)); ledger.endMeasurement(); ledger.freeze();
        var writer = new ResultReport();
        var initial = writer.build(config, ledger, context("finalize"), List.of(), false);
        Instant origin = Instant.parse("2026-10-16T08:00:00Z");
        initial.json().withObject("/measurementMetadata").put("resourceFinalizationVersion", 1)
                .put("measurementStartUtc", origin.toString()).put("observedDurationNanos", 2_000_000_000L);
        Path initialDirectory = directory.resolve("initial"); writer.write(initialDirectory, initial);
        Path samples = directory.resolve("samples.jsonl");
        StringBuilder jsonl = new StringBuilder();
        for (int i = 0; i <= 2; i++) {
            jsonl.append(mapper.writeValueAsString(new ResourceEvidence.Sample(origin.plusSeconds(i).toString(), origin.plusSeconds(i - 1).toString(),
                    1.0, 50.0, 25.0, 100L, 200L, 1000L, 2, null, null))).append('\n');
        }
        Files.writeString(samples, jsonl);
        Path sources = directory.resolve("resources.json");
        mapper.writeValue(sources.toFile(), new ExecutionSettings.ResourceFiles(1, "samples.jsonl", "samples.jsonl", null, 10));
        Path output = directory.resolve("final");
        assertEquals(0, HarnessMain.run(new String[]{"finalize-report", initialDirectory.resolve("results.json").toString(), sources.toString(), output.toString()}));
        var result = mapper.readTree(output.resolve("results.json").toFile());
        assertEquals("valid", result.at("/validity/status").asText());
        assertEquals(mapper.readTree(initial.json().path("overall").toString()), result.path("overall"));
        assertEquals(mapper.readTree(initial.json().path("cohortIntervals").toString()), result.path("cohortIntervals"));
        assertEquals("inconclusive", initial.json().at("/validity/status").asText());
        assertTrue(result.at("/measurementMetadata/resourcesFinalized").asBoolean());
    }

    @Test
    void cancellationAfterFrozenWindowDoesNotRewritePendingAdmissionAsTransportFailure() throws Exception {
        var config = config(); var ledger = new MessageLedger(config, "sealed", System::nanoTime, 0);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 10);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor(); server.setExecutor(executor);
        var received = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/sendsms", exchange -> { received.countDown(); try { release.await(); } catch (InterruptedException ignored) { } finally { exchange.close(); } });
        server.start();
        try (var client = new HttpAdmissionClient(config, new HttpIngressSettings(java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/sendsms"),
                "user", "secret", "from", "to", null), ledger)) {
            var submission = client.submit(true).orElseThrow(); assertTrue(received.await(3, TimeUnit.SECONDS));
            ledger.freeze(); client.close();
            assertEquals(MessageLedger.Admission.PENDING, submission.completion().get(3, TimeUnit.SECONDS));
            assertEquals(1, ledger.snapshot().pendingAdmission()); assertEquals(0, ledger.snapshot().failed());
            assertEquals(0, client.problems().get(HttpAdmissionClient.Problem.TRANSPORT_FAILURE));
        } finally { release.countDown(); server.stop(0); executor.shutdownNow(); }
    }
}
