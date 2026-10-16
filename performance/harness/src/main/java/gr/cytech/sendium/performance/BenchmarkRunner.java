package gr.cytech.sendium.performance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owns traffic services and local agent only. Application/remote agents are deployment-owned. */
public final class BenchmarkRunner {
    private final ObjectMapper mapper = HarnessMain.inputMapper();

    public int run(Path inputs, Path executionFile, Path output) throws Exception {
        RunConfig config = HarnessMain.configurationMapper().readValue(inputs.resolve("run-config.json").toFile(), RunConfig.class);
        ExecutionSettings execution = mapper.readValue(executionFile.toFile(), ExecutionSettings.class);
        MessageLedger ledger = new MessageLedger(config, execution.context().provenance().runIdentity(), System::nanoTime,
                TimeUnit.SECONDS.toNanos(execution.retentionSeconds()));
        output = output.toAbsolutePath().normalize();
        Files.createDirectory(output);
        execution.context().infrastructure().withObject("/harness").put("processId", ProcessHandle.current().pid());
        RunScenario scenario = new RunScenario(config, execution, executionFile.toAbsolutePath().getParent(), inputs, output, ledger);
        Thread owner = Thread.currentThread();
        CountDownLatch finished = new CountDownLatch(1);
        Thread shutdown = new Thread(() -> {
            owner.interrupt();
            try { finished.await(90, TimeUnit.SECONDS); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        }, "benchmark-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            try { new Lifecycle(scenario::phase).run(config, scenario); }
            catch (Exception exception) {
                boolean interrupted = exception instanceof InterruptedException;
                Thread.interrupted(); // Complete bounded cleanup/report publication before returning interruption status.
                scenario.observations.add(new RunValidity.Reason(interrupted ? "RUN_INTERRUPTED"
                        : scenario.phase == Lifecycle.Phase.READINESS ? "SETUP_FAILED" : "EXECUTION_FAILED",
                        "Benchmark stopped during " + scenario.phase + " (" + exception.getClass().getSimpleName() + ").", RunValidity.Severity.FAILED));
            }
            ResourceEvidence evidence = execution.resources().load(executionFile.toAbsolutePath().getParent(), scenario.localSamples);
            ResourceEvidence.Window window = scenario.measurementStart == null || ledger.timeline() == null ? null
                    : new ResourceEvidence.Window(scenario.measurementStart, scenario.measurementStart.plusNanos(ledger.timeline().durationNanos()));
            List<RunValidity.Reason> observations = new ArrayList<>(scenario.observations);
            if (scenario.http != null) { observations.addAll(RunValidity.httpAdmission(scenario.http.problems())); }
            if (scenario.smpp != null) { observations.addAll(RunValidity.smppAdmission(scenario.smpp.problems())); }
            if (scenario.provider != null) { observations.addAll(RunValidity.provider(scenario.provider.snapshot().problems())); }
            if (scenario.receiver != null) { observations.addAll(RunValidity.httpReceipts(scenario.receiver.problems())); }
            var writer = new ResultReport();
            List<String> diagnostics = new ArrayList<>(execution.context().diagnostics());
            diagnostics.addAll(List.of("state.json", "lifecycle.jsonl"));
            if (scenario.agent != null) { diagnostics.addAll(List.of("resource-agent.log", "harness-resources.jsonl")); }
            var context = new ResultReport.Context(execution.context().provenance(), execution.context().application(), execution.context().infrastructure(), diagnostics.stream().distinct().toList());
            var report = writer.build(config, ledger, context, scenario.statistics, scenario.drainTimedOut, evidence, window, observations);
            report.json().withObject("/drain").put("pendingReceiptAcknowledgements", scenario.pendingAcknowledgements);
            var metadata = report.json().withObject("/measurementMetadata");
            metadata.put("resourceFinalizationVersion", 1);
            metadata.put("observedDurationNanos", ledger.timeline() == null ? 0 : ledger.timeline().durationNanos());
            if (scenario.measurementStart == null) { metadata.putNull("measurementStartUtc"); }
            else { metadata.put("measurementStartUtc", scenario.measurementStart.toString()); }
            // Added metadata does not change Markdown measurements.
            writer.write(output, new ResultReport.Report(report.json(), ResultReport.markdown(report.json())));
            int exit = report.json().path("validity").path("exitCode").asInt();
            scenario.state("REPORTED", report.json().path("validity").path("status").asText(), exit);
            System.out.println("Benchmark result: " + report.json().path("validity").path("status").asText() + " (exit " + exit + ")");
            return exit;
        } finally {
            finished.countDown();
            try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) { }
        }
    }

    private final class RunScenario implements Scenario {
        final RunConfig config;
        final ExecutionSettings execution;
        final Path base, inputs, output;
        final MessageLedger ledger;
        final List<FixedRateGenerator.Statistics> statistics = new ArrayList<>();
        final List<RunValidity.Reason> observations = new ArrayList<>();
        Lifecycle.Phase phase = Lifecycle.Phase.READINESS;
        Instant measurementStart;
        boolean generating, sealed, closed, listenersReady, drainTimedOut;
        int pendingAcknowledgements;
        SmppProvider provider;
        HttpReceiptReceiver receiver;
        HttpAdmissionClient http;
        SmppAdmissionClient smpp;
        FixedRateGenerator generator;
        ResourceAgent agent;
        Path localSamples;

        RunScenario(RunConfig config, ExecutionSettings execution, Path base, Path inputs, Path output, MessageLedger ledger) {
            this.config = config; this.execution = execution; this.base = base; this.inputs = inputs; this.output = output; this.ledger = ledger;
        }

        void phase(Lifecycle.Phase value) {
            phase = value;
            try {
                Files.writeString(output.resolve("lifecycle.jsonl"), mapper.writeValueAsString(
                        java.util.Map.of("phase", value.name(), "timestamp", Instant.now().toString())) + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                state(value.name(), null, null);
            } catch (Exception exception) { throw new IllegalStateException("Cannot record lifecycle state"); }
        }

        void state(String value, String validity, Integer exit) throws Exception {
            var state = mapper.createObjectNode();
            state.put("schemaVersion", 1).put("runIdentity", execution.context().provenance().runIdentity())
                    .put("phase", value).put("listenersReady", listenersReady).put("harnessPid", ProcessHandle.current().pid());
            if (measurementStart != null) { state.put("measurementStartUtc", measurementStart.toString()); }
            if (ledger.timeline() != null) { state.put("observedDurationNanos", ledger.timeline().durationNanos()); }
            if (validity != null) { state.put("validity", validity).put("exitCode", exit); }
            Path temporary = output.resolve("state.tmp");
            Files.writeString(temporary, mapper.writeValueAsString(state) + "\n");
            try { Files.move(temporary, output.resolve("state.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException exception) { Files.move(temporary, output.resolve("state.json"), StandardCopyOption.REPLACE_EXISTING); }
        }

        @Override public void awaitReady() throws Exception {
            JsonNode runtime = mapper.readTree(inputs.resolve("runtime-settings.json").toFile());
            if (runtime.path("schemaVersion").asInt() != 1 || !runtime.path("runIdentity").asText().equals(execution.context().provenance().runIdentity())) {
                throw new IllegalArgumentException("Runtime identity does not match execution");
            }
            JsonNode effective = mapper.readTree(inputs.resolve("effective-configuration.json").toFile());
            if (effective.path("schemaVersion").asInt() != 1 || !effective.path("runIdentity").asText().equals(runtime.path("runIdentity").asText())
                    || !effective.path("workload").equals(mapper.valueToTree(config))) {
                throw new IllegalArgumentException("Effective configuration does not match the workload");
            }
            execution.context().application().set("effectiveConfiguration", effective);
            execution.context().application().put("dlrStorage", effective.path("dlrStorage").asText());
            JsonNode deployment = effective.path("deployment");
            var connections = execution.context().application().putObject("connections");
            connections.put("outgoingTransceivers", 1);
            for (String key : List.of("ingressWindow", "outgoingWindow", "providerWindow")) { connections.set(key, deployment.path(key)); }
            execution.context().infrastructure().put("preset", effective.path("preset").asText());
            execution.context().infrastructure().withObject("/sendium").set("host", deployment.path("sendiumHost"));
            execution.context().infrastructure().withObject("/harness").set("host", deployment.path("harnessHost"));
            deployment.path("database").properties().forEach(entry -> execution.context().infrastructure().withObject("/database").set(entry.getKey(), entry.getValue()));
            URI readiness = URI.create(runtime.path("readinessUri").asText());
            if (!List.of("http", "https").contains(readiness.getScheme()) || readiness.getHost() == null
                    || readiness.getUserInfo() != null || readiness.getQuery() != null || readiness.getFragment() != null) {
                throw new IllegalArgumentException("Invalid readiness endpoint");
            }
            provider = new SmppProvider(config, mapper.treeToValue(runtime.path("provider"), ProviderSettings.class), ledger);
            provider.start();
            if (config.ingressMode().equals("http")) {
                http = new HttpAdmissionClient(config, mapper.treeToValue(runtime.path("ingress"), HttpIngressSettings.class), ledger);
                if (config.dlrEnabled()) {
                    JsonNode callback = runtime.path("callbackReceiver");
                    receiver = new HttpReceiptReceiver(new InetSocketAddress(callback.path("host").asText(), callback.path("port").asInt()),
                            callback.path("path").asText(), callback.path("threads").asInt(), callback.path("queueCapacity").asInt(), true, ledger);
                    receiver.start();
                }
            } else { smpp = new SmppAdmissionClient(config, mapper.treeToValue(runtime.path("ingress"), SmppIngressSettings.class), ledger); }
            listenersReady = true;
            state("READINESS", null, null); // Deployment can now start Sendium without an initial failed provider bind.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(execution.readinessTimeoutSeconds());
            boolean ready = false;
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
            try {
                while (System.nanoTime() < deadline) {
                    java.util.concurrent.CompletableFuture<HttpResponse<String>> pending = null;
                    try {
                        long remaining = Math.max(1, deadline - System.nanoTime());
                        long timeout = Math.min(remaining, TimeUnit.SECONDS.toNanos(2));
                        pending = client.sendAsync(HttpRequest.newBuilder(readiness).timeout(Duration.ofNanos(timeout)).GET().build(),
                                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(), 65536));
                        var response = pending.get(timeout, TimeUnit.NANOSECONDS);
                        JsonNode health = mapper.readTree(response.body());
                        if (response.statusCode() == 200 && health.path("status").asText().equals("UP")) {
                            for (JsonNode check : health.path("checks")) {
                                if (check.path("name").asText().equals("sendium-dlr-storage") && check.path("status").asText().equals("UP")
                                        && check.path("data").path("backend").asText().equals("postgresql")) { ready = true; }
                            }
                        }
                        if (ready) { break; }
                    } catch (java.io.IOException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
                        if (pending != null) { pending.cancel(true); }
                    }
                    Thread.sleep(100);
                }
            } finally { client.shutdownNow(); }
            if (!ready || !provider.awaitBound(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())))) {
                throw new IllegalStateException("Application/database/provider readiness failed");
            }
            if (smpp != null) { smpp.connect(); }
            if (execution.sampling() != null) {
                if (execution.resources().harnessSamples() != null || execution.sampling().maxSamples() > execution.resources().sampleBudget()) {
                    throw new IllegalArgumentException("Conflicting sampler source or budget");
                }
                agent = new ResourceAgent(execution.sampling(), config, base, output); localSamples = agent.output();
            }
            generator = new FixedRateGenerator(config, ledger, (measured, scheduled) -> http != null
                    ? http.submit(measured, scheduled).isPresent() : smpp.submit(measured, scheduled).isPresent());
        }

        @Override public void startGeneration(Lifecycle.Phase value, long start, long deadline) throws Exception {
            if (value == Lifecycle.Phase.MEASUREMENT) {
                measurementStart = Instant.now().minusNanos(System.nanoTime() - start);
                ledger.beginMeasurement(start, deadline - start, execution.intervalBudget());
            }
            generator.start(value, start, deadline); generating = true;
            state(value.name(), null, null);
        }
        @Override public void stopGeneration() throws Exception {
            if (generating) { generating = false; statistics.add(generator.stop()); }
        }
        @Override public boolean hasOutstandingWork() {
            return ledger.hasOutstandingWork() || provider != null && provider.snapshot().pending() > 0
                    || http != null && http.pendingRequests() > 0 || receiver != null && receiver.pendingAcknowledgements() > 0
                    || smpp != null && (smpp.pendingRequests() > 0 || smpp.bufferedReceipts() > 0 || smpp.pendingReceiptAcknowledgements() > 0);
        }
        @Override public void finalizeAccounting() throws Exception { seal(true); }
        private void seal(boolean normalEnd) throws Exception {
            if (sealed) { return; }
            stopGeneration();
            ledger.endMeasurement(); ledger.finishDrain();
            if (smpp != null && smpp.pendingRequests() == 0) { smpp.finalizeReceipts(); }
            drainTimedOut = normalEnd && hasOutstandingWork();
            pendingAcknowledgements = (receiver == null ? 0 : receiver.pendingAcknowledgements()) + (smpp == null ? 0 : smpp.pendingReceiptAcknowledgements());
            ledger.freeze(); sealed = true;
        }
        @Override public void close() {
            if (closed) { return; } closed = true;
            try { seal(false); } catch (Exception exception) {
                observations.add(new RunValidity.Reason("ACCOUNTING_FINALIZATION_FAILED", "Could not finalize traffic accounting.", RunValidity.Severity.FAILED));
                ledger.freeze();
            }
            closeResource(generator); closeResource(provider); closeResource(http); closeResource(smpp); closeResource(receiver); closeResource(agent);
            if (agent != null && !agent.completedSuccessfully()) {
                observations.add(new RunValidity.Reason("RESOURCE_AGENT_FAILED", "Harness resource agent did not finish successfully.", RunValidity.Severity.INCONCLUSIVE, "resources"));
            }
        }
        private void closeResource(AutoCloseable resource) {
            if (resource != null) {
                try { resource.close(); } catch (Exception exception) {
                    observations.add(new RunValidity.Reason("HARNESS_CLEANUP_FAILED", "A harness service failed to close.", RunValidity.Severity.FAILED));
                }
            }
        }
    }
}
