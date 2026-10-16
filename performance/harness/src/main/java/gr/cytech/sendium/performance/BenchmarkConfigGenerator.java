package gr.cytech.sendium.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** Produces separate private runtime bundles and a sanitized configuration artifact. */
public final class BenchmarkConfigGenerator {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ObjectMapper mapper = new ObjectMapper();

    public record Generated(Map<String, String> files) {
        public Generated { files = Map.copyOf(files); }
        @Override public String toString() { return "Generated[private runtime files=" + files.size() + "]"; }
    }

    public Generated generate(RunConfig workload, BenchmarkDeployment deployment) throws Exception {
        if ("smpp".equals(workload.ingressMode()) && deployment.ingressWindow() < workload.maxOutstandingRequests()
                || deployment.outgoingRequestTimeoutMillis() <= workload.providerResponseDelayMillis()) {
            throw new IllegalArgumentException("Window capacity or provider response timeout is inconsistent");
        }
        String user = "perf-" + secret(4);
        String password = secret(16);
        String providerUser = "smsc-" + secret(4);
        String providerPassword = secret(16);
        boolean smpp = "smpp".equals(workload.ingressMode());
        var provider = new ProviderSettings("0.0.0.0", deployment.providerPort(), providerUser,
                providerPassword, 1, deployment.providerWindow(), deployment.providerMaxPending(), deployment.providerIoTimeoutMillis());
        URI callback = uri(deployment.harnessHost(), deployment.callbackPort(), "/dlr");
        var ingress = smpp ? new SmppIngressSettings(deployment.sendiumHost(), deployment.smppPort(),
                user, password, "Benchmark", "306900000001", deployment.ingressWindow(), deployment.connectTimeoutMillis())
                : new HttpIngressSettings(uri(deployment.sendiumHost(), deployment.httpPort(), "/sendsms"),
                    user, password, "Benchmark", "306900000001", workload.dlrEnabled() ? callback : null);

        Map<String, String> workers = new TreeMap<>();
        String upstream = "outSms.instance.provider.";
        workers.put("message.trace.mode", "off");
        workers.put(upstream + "enable", "true");
        workers.put(upstream + "type", "smppclient");
        workers.put(upstream + "host", deployment.harnessHost());
        workers.put(upstream + "port", "" + deployment.providerPort());
        workers.put(upstream + "username", providerUser);
        workers.put(upstream + "password", providerPassword);
        workers.put(upstream + "tps", "" + workload.workerTps());
        workers.put(upstream + "threadCount", "" + deployment.outgoingWorkerThreads());
        workers.put(upstream + "connections.transceivers", "1");
        workers.put(upstream + "connections.transmitters", "0");
        workers.put(upstream + "connections.receivers", "0");
        workers.put(upstream + "windowSize", "" + deployment.outgoingWindow());
        workers.put(upstream + "con.tout", "" + deployment.connectTimeoutMillis());
        workers.put(upstream + "request.tout", "" + deployment.outgoingRequestTimeoutMillis());
        workers.put(upstream + "window.monitor.interval.millis", "" + Math.max(1, Math.min(1000, deployment.outgoingRequestTimeoutMillis() / 2)));
        workers.put(upstream + "connection.healthcheck", "true");
        workers.put(upstream + "msg.hash.prefix", "benchmark-" + deployment.runIdentity());
        workers.put(upstream + "status.retry.worker", "");
        workers.put(upstream + "status.retry.router", "");
        workers.put(upstream + "status.default", "FAIL");
        workers.put(upstream + "maxRetries", "1");
        workers.put(upstream + "print.msgs", "false");
        workers.put(upstream + "log.bytes", "false");
        workers.put(upstream + "log.pdus", "false");
        String downstream = "outSms.instance.smpp.";
        workers.put(downstream + "enable", "" + smpp);
        workers.put(downstream + "type", "smppserver");
        if (smpp) {
            workers.put(downstream + "tps", "0");
            workers.put(downstream + "print.msgs", "false");
            workers.put(downstream + "srv.host", "0.0.0.0");
            workers.put(downstream + "srv.port", "" + deployment.smppPort());
            workers.put(downstream + "srv.bindTimeout", "" + deployment.connectTimeoutMillis());
            workers.put(downstream + "srv.defaultRequestExpiryTimeout", "" + workload.admissionTimeoutMillis());
            workers.put(downstream + "srv.defaultWindowMonitorInterval", "" + Math.max(1, Math.min(1000, workload.admissionTimeoutMillis() / 2)));
            workers.put(downstream + "srv.defaultWindowSize", "" + deployment.ingressWindow());
            workers.put(downstream + "srv.maxConnections", "1");
            workers.put(downstream + "srv.maxConnectionsPerIP", "1");
            workers.put(downstream + "conf.maxConnectionsPerUser.default", "1");
            workers.put(downstream + "conf.maxPending.default", "" + workload.maxOutstandingRequests());
            workers.put(downstream + "conf.maxRate.default", "0");
        }

        Map<String, String> application = new TreeMap<>();
        application.put("smsg.properties.file.path", "conf/smsg.properties");
        application.put("smsg.credentials.file.path", "conf/credentials.yml");
        application.put("smsg.routing.file.path", "conf/routingTable.conf");
        application.put("quarkus.http.host", "0.0.0.0");
        application.put("quarkus.http.port", "" + deployment.httpPort());
        application.put("quarkus.http.access-log.enabled", "false");
        application.put("quarkus.log.level", "INFO");
        application.put("quarkus.datasource.dlr.jdbc.url", "${SENDIUM_DLR_POSTGRESQL_JDBC_URL}");
        application.put("quarkus.datasource.dlr.username", "${SENDIUM_DLR_POSTGRESQL_USERNAME}");
        application.put("quarkus.datasource.dlr.password", "${SENDIUM_DLR_POSTGRESQL_PASSWORD}");
        var database = deployment.database();
        application.put("quarkus.datasource.dlr.jdbc.min-size", "" + database.poolMinSize());
        application.put("quarkus.datasource.dlr.jdbc.max-size", "" + database.poolMaxSize());
        application.put("quarkus.datasource.dlr.jdbc.acquisition-timeout", database.acquisitionTimeoutMillis() + "ms");
        application.put("sendium.dlr.delivery-claim-duration", database.claimDurationSeconds() + "s");

        String routing = """
                [default]
                MESSAGE:type:==:0
                MESSAGE:type:==:11
                MESSAGE:type:==:14
                MESSAGE:type:==:17
                MESSAGE:type:==:10
                smppserver.smpp:type:==:18

                [MESSAGE]
                provider::default:
                """;
        Map<String, String> files = new LinkedHashMap<>();
        files.put("sendium/conf/smsg.properties", properties(workers));
        files.put("sendium/config/application.properties", properties(application));
        files.put("sendium/conf/routingTable.conf", routing);
        files.put("sendium/conf/credentials.yml", """
                credentials:
                  - type: %s
                    accountId: "benchmark-%s"
                    systemId: "%s"
                    password: "%s"
                """.formatted(smpp ? "SMPP" : "HTTP", deployment.runIdentity(), user, password));
        files.put("sendium/environment.json", json(Map.of(
                "SENDIUM_DLR_POSTGRESQL_JDBC_URL", database.jdbcUrl(),
                "SENDIUM_DLR_POSTGRESQL_USERNAME", database.username(),
                "SENDIUM_DLR_POSTGRESQL_PASSWORD", database.password())));
        Map<String, Object> harness = new LinkedHashMap<>();
        harness.put("schemaVersion", 1);
        harness.put("runIdentity", deployment.runIdentity());
        harness.put("readinessUri", uri(deployment.sendiumHost(), deployment.httpPort(), "/q/health/ready"));
        harness.put("provider", provider);
        harness.put("ingress", ingress);
        harness.put("callbackReceiver", Map.of("host", "0.0.0.0", "port", deployment.callbackPort(),
                "path", "/dlr", "threads", deployment.callbackThreads(), "queueCapacity", deployment.callbackQueueCapacity()));
        files.put("harness/runtime-settings.json", json(harness));
        files.put("harness/run-config.json", json(workload));

        Map<String, String> publicWorkers = new TreeMap<>(workers);
        publicWorkers.remove(upstream + "username");
        publicWorkers.remove(upstream + "password");
        Map<String, Object> effective = new LinkedHashMap<>();
        effective.put("schemaVersion", 1);
        effective.put("workload", workload);
        effective.put("runIdentity", deployment.runIdentity());
        effective.put("preset", deployment.preset());
        effective.put("applicationProperties", application);
        effective.put("workerProperties", publicWorkers);
        effective.put("routing", routing);
        effective.put("dlrStorage", "postgresql");
        effective.put("dlrRequested", workload.dlrEnabled());
        com.fasterxml.jackson.databind.node.ObjectNode publicDeployment = mapper.valueToTree(deployment);
        publicDeployment.withObject("/database").remove(java.util.List.of("password", "username"));
        effective.put("deployment", publicDeployment);
        files.put("public/effective-configuration.json", json(effective));
        files.put("harness/effective-configuration.json", files.get("public/effective-configuration.json"));
        return new Generated(Map.copyOf(files));
    }

    /** Refuse existing output trees; each invocation owns a new, private run directory. */
    public void write(Path directory, Generated generated) throws IOException {
        for (String name : generated.files().keySet()) {
            Path relative = Path.of(name);
            if (relative.isAbsolute() || relative.normalize().startsWith("..")) {
                throw new IllegalArgumentException("Generated path must stay inside the output directory");
            }
        }
        boolean posix = directory.toAbsolutePath().getParent().getFileSystem().supportedFileAttributeViews().contains("posix");
        if (posix) {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else { Files.createDirectory(directory); }
        for (var file : generated.files().entrySet()) {
            Path target = directory.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            if (posix) {
                Files.createFile(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else { Files.createFile(target); }
            Files.writeString(target, file.getValue(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private String json(Object value) throws IOException { return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n"; }
    private static String secret(int bytes) {
        byte[] random = new byte[bytes]; RANDOM.nextBytes(random); return HexFormat.of().formatHex(random);
    }
    private static URI uri(String host, int port, String path) throws Exception { return new URI("http", null, host, port, path, null, null); }
    private static String properties(Map<String, String> values) throws IOException {
        Properties properties = new Properties(); properties.putAll(values);
        StringWriter writer = new StringWriter(); properties.store(writer, "Generated benchmark configuration"); return writer.toString();
    }
}
