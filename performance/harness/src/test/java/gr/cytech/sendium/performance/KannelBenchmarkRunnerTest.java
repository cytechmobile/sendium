package gr.cytech.sendium.performance;

import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.netty.channel.nio.NioEventLoopGroup;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class KannelBenchmarkRunnerTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private RunConfig config(String ingress) { return new RunConfig(1, ingress, 1, 0, 0, 1, 0, false, 0, 0, 1000, 10, 10); }

    private Path execution() throws Exception {
        Path path = directory.resolve("execution.json");
        mapper.writeValue(path.toFile(), new ExecutionSettings(1, 5, 30, 256,
                KannelFixtures.context("runner-kannel"), null, null));
        return path;
    }

    private Path inputs(URI uri, int providerPort, String runtimeTarget) throws Exception {
        Path inputs = Files.createDirectory(directory.resolve("inputs"));
        mapper.writeValue(inputs.resolve("run-config.json").toFile(), config("http"));
        var runtime = mapper.createObjectNode().put("schemaVersion", 1).put("runIdentity", "runner-kannel")
                .put("benchmarkTarget", runtimeTarget).put("readinessUri", uri.toString());
        runtime.set("provider", mapper.valueToTree(new ProviderSettings("127.0.0.1", providerPort, "provider", "fixture", 1, 10, 10, 1000)));
        runtime.set("ingress", mapper.valueToTree(new HttpIngressSettings(uri, "fixture", "fixture", "from", "123456", null)));
        mapper.writeValue(inputs.resolve("runtime-settings.json").toFile(), runtime);
        var effective = mapper.createObjectNode().put("schemaVersion", 1).put("runIdentity", "runner-kannel")
                .put("benchmarkTarget", "kannel").put("preset", "kannel-unit-fixture")
                .put("dlrStorage", "internal").put("messageStore", "spool");
        effective.set("source", KannelFixtures.context("runner-kannel").application().path("source"));
        effective.set("workload", mapper.valueToTree(config("http")));
        effective.putObject("deployment").put("gatewayHost", "127.0.0.1").put("harnessHost", "127.0.0.1")
                .put("ingressWindow", 10).put("outgoingWindow", 10).put("providerWindow", 10);
        mapper.writeValue(inputs.resolve("effective-configuration.json").toFile(), effective);
        return inputs;
    }

    @Test
    void runnerRequiresProviderBindAndUsesKannelReadinessWithoutAFalsePostgresqlDependency() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 10);
        server.createContext("/sendsms", exchange -> {
            try (exchange) {
                boolean readiness = exchange.getRequestURI().getRawQuery() == null;
                byte[] body = (readiness ? "Authorization failed for sendsms" : "Temporal failure, try again later.")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(readiness ? 403 : 503, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        int port;
        try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        var group = new NioEventLoopGroup(1);
        var client = new DefaultSmppClient(group);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Path inputs = inputs(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/sendsms"), port, "kannel");
            Path execution = execution();
            Path output = directory.resolve("report");
            var run = executor.submit(() -> HarnessMain.run(new String[]{"run", inputs.toString(), execution.toString(), output.toString()}));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while ((!Files.exists(output.resolve("state.json"))
                        || !mapper.readTree(output.resolve("state.json").toFile()).path("listenersReady").asBoolean())
                        && System.nanoTime() < deadline) { Thread.sleep(10); }
                assertTrue(Files.exists(output.resolve("state.json")));
                assertFalse(run.isDone(), "HTTP readiness alone must not bypass the provider bind");
                var settings = new SmppSessionConfiguration(SmppBindType.TRANSCEIVER, "provider", "fixture");
                settings.setHost("127.0.0.1"); settings.setPort(port); settings.setBindTimeout(2000);
                settings.getLoggingOptions().setLogPdu(false); settings.getLoggingOptions().setLogBytes(false);
                var session = client.bind(settings, new DefaultSmppSessionHandler());
                try { assertEquals(0, run.get(10, TimeUnit.SECONDS)); }
                finally { session.destroy(); }
                var report = mapper.readTree(output.resolve("results.json").toFile());
                assertEquals("inconclusive", report.at("/validity/status").asText()); // Resource sampling is a later task.
                assertEquals(1, report.at("/overall/counts/rejected").asInt());
                assertEquals(0, report.at("/overall/counts/failed").asInt());
                assertFalse(report.at("/validity/reasons").toString().contains("SETUP_FAILED"));
                assertTrue(report.path("resources").has("kannel"));
                assertFalse(report.at("/configuration/infrastructure").has("sendium"));
            } finally { run.cancel(true); }
        } finally {
            server.stop(0); client.destroy(0, 100);
            group.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    void targetMismatchFailsBeforeListenersAndKannelSmppIsRejectedBeforeExecution() throws Exception {
        Path inputs = inputs(URI.create("http://127.0.0.1:1/sendsms"), 1, "sendium");
        Path execution = execution();
        Path output = directory.resolve("mismatched");
        assertEquals(1, HarnessMain.run(new String[]{"run", inputs.toString(), execution.toString(), output.toString()}));
        assertTrue(mapper.readTree(output.resolve("results.json").toFile()).at("/validity/reasons").toString().contains("SETUP_FAILED"));
        assertFalse(mapper.readTree(output.resolve("state.json").toFile()).path("listenersReady").asBoolean());
        mapper.writeValue(inputs.resolve("run-config.json").toFile(), config("smpp"));
        Path invalid = directory.resolve("unsupported");
        assertEquals(2, HarnessMain.run(new String[]{"run", inputs.toString(), execution.toString(), invalid.toString()}));
        assertFalse(Files.exists(invalid));
    }
}
