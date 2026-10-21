package gr.cytech.sendium.performance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import static gr.cytech.sendium.performance.MessageLedger.Admission.ACCEPTED;

/** Functional verification, not a throughput benchmark. Requires a packaged app and Docker. */
class PackagedSendiumIT {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("postgres").withUsername("benchmark").withPassword(UUID.randomUUID().toString());
    private static Path applicationJar;
    private static Path outputs;

    @BeforeAll
    static void startDatabase() throws Exception {
        applicationJar = Path.of(System.getProperty("sendium.app.jar")).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(applicationJar), "Package sendium-app before running performance integration checks");
        outputs = Path.of(System.getProperty("integration.output.directory")).toAbsolutePath().normalize();
        Files.createDirectories(outputs);
        POSTGRES.start();
    }

    @AfterAll
    static void stopDatabase() { POSTGRES.stop(); }

    @ParameterizedTest(name = "{0} ingress, delivery reports={1}")
    @CsvSource({"http,true", "http,false", "smpp,true", "smpp,false"})
    void generatedConfigurationWorksAgainstPackagedSendium(String mode, boolean dlr) throws Exception {
        String identity = mode + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String databaseName = "benchmark_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }
        String jdbc = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
                + "/" + databaseName + "?connectTimeout=3&socketTimeout=5";
        int[] ports = freePorts();
        RunConfig config = new RunConfig(1, mode, 10, 100, 1, 1, 20, dlr, 10, 1000, 5000, 10, 100);
        BenchmarkDeployment deployment = new BenchmarkDeployment(identity, "local-integration", "127.0.0.1", "127.0.0.1",
                ports[0], ports[1], ports[2], ports[3], 10, 10, 10, 100,
                5000, 10000, 5000, 1, 2, 20,
                new BenchmarkDeployment.Database(jdbc, POSTGRES.getUsername(), POSTGRES.getPassword(), 0, 10, 5000, 300));
        Path run = outputs.resolve(identity);
        BenchmarkConfigGenerator generator = new BenchmarkConfigGenerator();
        var generated = generator.generate(config, deployment);
        generator.write(run, generated);
        JsonNode runtime = MAPPER.readTree(generated.files().get("harness/runtime-settings.json"));
        MessageLedger ledger = new MessageLedger(config, identity, System::nanoTime, TimeUnit.SECONDS.toNanos(30));
        Path logs = run.resolve("diagnostics");
        Files.createDirectory(logs);
        try (SmppProvider provider = new SmppProvider(config,
                    MAPPER.treeToValue(runtime.path("provider"), ProviderSettings.class), ledger);
                HttpReceiptReceiver receiver = new HttpReceiptReceiver(new InetSocketAddress("0.0.0.0", ports[3]),
                        "/dlr", 2, 20, dlr, ledger);
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            provider.start();
            receiver.start();
            Process application = startApplication(run.resolve("sendium"), logs.resolve("sendium.log"));
            try {
                URI base = URI.create("http://127.0.0.1:" + ports[0]);
                awaitReadiness(http, base, application, logs.resolve("sendium.log"));
                assertTrue(provider.awaitBound(Duration.ofSeconds(10)), "Packaged application did not bind to provider");
                if (mode.equals("http")) {
                    try (HttpAdmissionClient client = new HttpAdmissionClient(config,
                            MAPPER.treeToValue(runtime.path("ingress"), HttpIngressSettings.class), ledger)) {
                        verifyTraffic(config, ledger, provider, () -> client.submit(false).orElseThrow().completion(),
                                () -> client.submit(true).orElseThrow().completion());
                        assertTrue(client.problems().values().stream().allMatch(value -> value == 0), client.problems().toString());
                    }
                } else {
                    try (SmppAdmissionClient client = new SmppAdmissionClient(config,
                            MAPPER.treeToValue(runtime.path("ingress"), SmppIngressSettings.class), ledger)) {
                        client.connect();
                        verifyTraffic(config, ledger, provider, () -> client.submit(false).orElseThrow().completion(),
                                () -> client.submit(true).orElseThrow().completion());
                        client.finalizeReceipts();
                        assertTrue(client.problems().values().stream().allMatch(value -> value == 0), client.problems().toString());
                    }
                }
                assertTrue(receiver.problems().values().stream().allMatch(value -> value == 0), receiver.problems().toString());
                verifyDatabase(http, base, jdbc, dlr);
                ledger.freeze();
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(logs.resolve("accounting.json").toFile(), ledger.snapshot());
            } finally {
                stopApplication(application);
            }
        }
    }

    private interface Submission { CompletableFuture<MessageLedger.Admission> send(); }

    @ParameterizedTest(name = "packaged CLI: {0}, delivery reports={1}")
    @CsvSource({"http,true", "http,false", "smpp,true", "smpp,false"})
    void executableCommandRunsAllPhasesAndPublishesReports(String mode, boolean dlr) throws Exception {
        String identity = "cli-" + mode + "-" + UUID.randomUUID().toString().substring(0, 8);
        String databaseName = "benchmark_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }
        String jdbc = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + databaseName;
        int[] ports = freePorts();
        var config = new RunConfig(1, mode, 2, 100, 1, 2, 10, dlr, 10, 500, 5000, 10, 100);
        var deployment = new BenchmarkDeployment(identity, "cli-integration", "127.0.0.1", "127.0.0.1", ports[0], ports[1], ports[2], ports[3],
                10, 10, 10, 100, 5000, 10000, 5000, 1, 2, 20,
                new BenchmarkDeployment.Database(jdbc, POSTGRES.getUsername(), POSTGRES.getPassword(), 0, 10, 5000, 300));
        Path run = outputs.resolve(identity);
        var generator = new BenchmarkConfigGenerator(); generator.write(run, generator.generate(config, deployment));
        var appContext = MAPPER.createObjectNode(); appContext.putArray("jvmArguments"); appContext.putObject("connections").put("outgoingTransceivers", 1); appContext.put("dlrStorage", "postgresql");
        var infrastructure = MAPPER.createObjectNode(); infrastructure.put("preset", "cli-integration").put("region", "local");
        infrastructure.putObject("sendium"); infrastructure.putObject("harness"); infrastructure.putObject("database").put("engine", "postgresql");
        var context = new ResultReport.Context(new ResultReport.Provenance("integration-fixture", "a".repeat(40), "b".repeat(40), null, identity, List.of()),
                appContext, infrastructure, List.of("lifecycle.jsonl"));
        Path execution = run.resolve("execution.json");
        MAPPER.writeValue(execution.toFile(), new ExecutionSettings(1, 30, 30, 256, context, null, null));
        Path output = run.resolve("report");
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path harnessJar = Path.of("target", "sendium-performance-harness-0.5.2-SNAPSHOT.jar").toAbsolutePath();
        Process harness = new ProcessBuilder(java.toString(), "-jar", harnessJar.toString(), "run", run.resolve("harness").toString(), execution.toString(), output.toString())
                .redirectErrorStream(true).redirectOutput(run.resolve("harness.log").toFile()).start();
        Process application = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean listening = false;
            while (harness.isAlive() && System.nanoTime() < deadline) {
                if (Files.exists(output.resolve("state.json"))) {
                    listening = MAPPER.readTree(output.resolve("state.json").toFile()).path("listenersReady").asBoolean();
                    if (listening) { break; }
                }
                Thread.sleep(20);
            }
            assertTrue(listening, "CLI did not become ready; inspect " + run.resolve("harness.log"));
            application = startApplication(run.resolve("sendium"), run.resolve("sendium.log"));
            assertTrue(harness.waitFor(45, TimeUnit.SECONDS), "CLI did not finish");
            assertEquals(0, harness.exitValue(), "CLI failed; inspect " + output.resolve("results.json"));
            var report = MAPPER.readTree(output.resolve("results.json").toFile());
            assertNotEquals("failed", report.at("/validity/status").asText());
            assertEquals(4, report.at("/overall/counts/scheduled").asLong());
            assertEquals(4, report.at("/overall/counts/accepted").asLong());
            assertEquals(4, report.at("/overall/counts/providerResponses").asLong());
            assertEquals(dlr ? 4 : 0, report.at("/overall/counts/receiptsForwarded").asLong());
            assertEquals(0, report.at("/drain/unfinishedAccepted").asLong());
            assertEquals(2, report.at("/measurementMetadata/generatorPhases").size());
            assertEquals("100", report.at("/configuration/application/effectiveConfiguration/workerProperties/outSms.instance.provider.tps").asText());
            String providerPassword = MAPPER.readTree(run.resolve("harness/runtime-settings.json").toFile()).at("/provider/password").asText();
            assertFalse(Files.readString(output.resolve("results.json")).contains(providerPassword));
            assertTrue(Files.isRegularFile(output.resolve("summary.md")));
            try (HttpClient http = HttpClient.newHttpClient()) { verifyDatabase(http, URI.create("http://127.0.0.1:" + ports[0]), jdbc, dlr, 6); }
        } finally { stopApplication(harness); if (application != null) { stopApplication(application); } }
    }

    private static void verifyTraffic(RunConfig config, MessageLedger ledger, SmppProvider provider,
            Submission warmup, Submission measurement) throws Exception {
        // Small functional cohorts exercise the same adapters; fixed-rate phases are Task 3.1.
        List<CompletableFuture<MessageLedger.Admission>> admissions = new ArrayList<>();
        for (int i = 0; i < 2; i++) { admissions.add(warmup.send()); }
        for (int i = 0; i < 3; i++) { admissions.add(measurement.send()); }
        for (var admission : admissions) { assertEquals(ACCEPTED, admission.get(10, TimeUnit.SECONDS)); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.drainTimeoutSeconds());
        while ((ledger.hasOutstandingWork() || provider.snapshot().pending() > 0) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(ledger.hasOutstandingWork(), ledger.snapshot().toString());
        assertEquals(0, provider.snapshot().pending());
        assertTrue(provider.snapshot().problems().values().stream().allMatch(value -> value == 0), provider.snapshot().toString());
        var counts = ledger.snapshot();
        assertEquals(3, counts.scheduled());
        assertEquals(3, counts.accepted());
        assertEquals(3, counts.providerResponses());
        assertEquals(config.dlrEnabled() ? 3 : 0, counts.receiptsForwarded());
        assertEquals(0, counts.violations());
        assertEquals(0, counts.missed());
        assertEquals(0, counts.acceptedAwaitingProvider());
        assertEquals(0, counts.acceptedAwaitingReceipt());
    }

    private static Process startApplication(Path workingDirectory, Path log) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-jar", applicationJar.toString())
                .directory(workingDirectory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        // Keep this test's effective configuration independent of the invoking shell's application settings.
        builder.environment().keySet().removeIf(key -> key.startsWith("QUARKUS_") || key.startsWith("SENDIUM_")
                || key.startsWith("SMSG_") || key.equals("LOG_LEVEL"));
        JsonNode environment = MAPPER.readTree(workingDirectory.resolve("environment.json").toFile());
        environment.properties().forEach(entry -> builder.environment().put(entry.getKey(), entry.getValue().asText()));
        return builder.start();
    }

    private static void awaitReadiness(HttpClient http, URI base, Process process, Path log) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            assertTrue(process.isAlive(), "Packaged application exited; inspect " + log);
            try {
                var response = get(http, base.resolve("/q/health/ready"));
                JsonNode readiness = MAPPER.readTree(response.body());
                if (response.statusCode() == 200 && "UP".equals(readiness.path("status").asText())) {
                    for (JsonNode check : readiness.path("checks")) {
                        if ("sendium-dlr-storage".equals(check.path("name").asText())
                                && "postgresql".equals(check.path("data").path("backend").asText())) { return; }
                    }
                }
            } catch (java.io.IOException ignored) { }
            Thread.sleep(100);
        }
        fail("Packaged application did not become PostgreSQL-ready; inspect " + log);
    }

    private static void verifyDatabase(HttpClient http, URI base, String jdbc, boolean dlr) throws Exception {
        verifyDatabase(http, base, jdbc, dlr, 5);
    }

    private static void verifyDatabase(HttpClient http, URI base, String jdbc, boolean dlr, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String metrics;
        do {
            metrics = get(http, base.resolve("/q/metrics")).body();
            if (metricCount(metrics, "record_provider_accepted") == (dlr ? expected : 0)
                    && metricCount(metrics, "complete_delivery") >= (dlr ? expected : 0)) { break; }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        assertEquals(dlr ? expected : 0, metricCount(metrics, "record_provider_accepted"));
        assertEquals(dlr ? expected : 0, metricCount(metrics, "resolve"));
        assertTrue(metricCount(metrics, "complete_delivery") >= (dlr ? expected : 0));
        assertFalse(metrics.lines().anyMatch(line -> line.startsWith("sendium_dlr_storage_operation_seconds_count")
                && line.contains("outcome=\"error\"") && DlrMetricSamples.parseValue(line) > 0));
        try (var connection = DriverManager.getConnection(jdbc, POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            for (String table : List.of("dlr_message", "provider_correlation")) {
                try (var rows = statement.executeQuery("SELECT count(*) FROM sendium_dlr." + table)) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getLong(1), "Completed scenario left database work in " + table);
                }
            }
        }
    }

    private static double metricCount(String metrics, String operation) {
        return metrics.lines().filter(line -> line.startsWith("sendium_dlr_storage_operation_seconds_count"))
                .filter(line -> line.contains("backend=\"postgresql\"") && line.contains("operation=\"" + operation + "\"")
                        && line.contains("outcome=\"success\""))
                .mapToDouble(DlrMetricSamples::parseValue).sum();
    }

    private static HttpResponse<String> get(HttpClient http, URI uri) throws Exception {
        return http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(), 1_048_576));
    }

    private static void stopApplication(Process application) throws Exception {
        application.destroy();
        if (!application.waitFor(15, TimeUnit.SECONDS)) {
            application.destroyForcibly();
            assertTrue(application.waitFor(10, TimeUnit.SECONDS), "Could not stop packaged application");
        }
    }

    private static int[] freePorts() throws Exception {
        List<ServerSocket> sockets = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) { sockets.add(new ServerSocket(0)); }
            int[] ports = sockets.stream().mapToInt(ServerSocket::getLocalPort).toArray();
            assertEquals(4, new HashSet<>(sockets.stream().map(ServerSocket::getLocalPort).toList()).size());
            return ports;
        } finally { for (ServerSocket socket : sockets) { socket.close(); } }
    }
}
