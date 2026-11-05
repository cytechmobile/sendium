package gr.cytech.sendium.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkConfigGeneratorTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final BenchmarkConfigGenerator generator = new BenchmarkConfigGenerator();

    private BenchmarkDeployment deployment() throws Exception {
        return mapper.readValue(Path.of("src/test/resources/deployment.json").toFile(), BenchmarkDeployment.class);
    }
    private RunConfig workload(String mode, boolean dlr) {
        return new RunConfig(1, mode, 500, 321, 120, 600, 120, dlr, 5, 1000, 10000, 1000, 10000);
    }
    private Properties properties(String text) throws Exception {
        Properties properties = new Properties(); properties.load(new StringReader(text)); return properties;
    }

    @ParameterizedTest
    @CsvSource({"http,true", "http,false", "smpp,true", "smpp,false"})
    void generatedBundlesAgreeOnCredentialsRoutingEndpointsAndDlrSemantics(String mode, boolean dlr) throws Exception {
        var deployment = deployment();
        var files = generator.generate(workload(mode, dlr), deployment).files();
        var workers = properties(files.get("sendium/conf/smsg.properties"));
        var application = properties(files.get("sendium/config/application.properties"));
        var harness = mapper.readTree(files.get("harness/runtime-settings.json"));
        var credentials = new ObjectMapper(new YAMLFactory()).readTree(files.get("sendium/conf/credentials.yml"));
        var credential = credentials.path("credentials").get(0);
        assertEquals(mode.toUpperCase(), credential.path("type").asText());
        assertEquals(1, credentials.path("credentials").size());
        assertEquals(credential.path("systemId").asText(), harness.path("ingress").path(mode.equals("http") ? "username" : "systemId").asText());
        assertEquals(credential.path("password").asText(), harness.path("ingress").path("password").asText());
        assertEquals("321", workers.getProperty("outSms.instance.provider.tps"));
        assertEquals("1000", workers.getProperty("outSms.instance.provider.windowSize"));
        assertEquals("1", workers.getProperty("outSms.instance.provider.connections.transceivers"));
        assertEquals("0", workers.getProperty("outSms.instance.provider.connections.transmitters"));
        assertEquals("0", workers.getProperty("outSms.instance.provider.connections.receivers"));
        assertEquals(harness.path("provider").path("systemId").asText(), workers.getProperty("outSms.instance.provider.username"));
        assertEquals(harness.path("provider").path("password").asText(), workers.getProperty("outSms.instance.provider.password"));
        assertEquals("127.0.0.1", workers.getProperty("outSms.instance.provider.host"));
        assertEquals("27779", workers.getProperty("outSms.instance.provider.port"));
        assertEquals("benchmark-local-test-1", workers.getProperty("outSms.instance.provider.msg.hash.prefix"));
        assertEquals("FAIL", workers.getProperty("outSms.instance.provider.status.default"));
        assertEquals("", workers.getProperty("outSms.instance.provider.status.retry.worker"));
        assertEquals("false", workers.getProperty("outSms.instance.provider.log.pdus"));
        assertEquals("false", workers.getProperty("outSms.instance.provider.log.bytes"));
        assertEquals("off", workers.getProperty("message.trace.mode"));
        assertEquals("" + mode.equals("smpp"), workers.getProperty("outSms.instance.smpp.enable"));
        if (mode.equals("smpp")) {
            assertEquals("27777", workers.getProperty("outSms.instance.smpp.srv.port"));
            assertEquals("0", workers.getProperty("outSms.instance.smpp.tps"));
            assertEquals("0", workers.getProperty("outSms.instance.smpp.conf.maxRate.default"));
            mapper.treeToValue(harness.path("ingress"), SmppIngressSettings.class);
        } else {
            var ingress = mapper.treeToValue(harness.path("ingress"), HttpIngressSettings.class);
            assertEquals("http://127.0.0.1:18080/sendsms", ingress.admissionUri().toString());
            assertEquals(dlr ? "http://127.0.0.1:18081/dlr" : null,
                    ingress.callbackBase() == null ? null : ingress.callbackBase().toString());
        }
        mapper.treeToValue(harness.path("provider"), ProviderSettings.class);
        assertEquals("conf/smsg.properties", application.getProperty("smsg.properties.file.path"));
        assertEquals("18080", application.getProperty("quarkus.http.port"));
        assertEquals("${SENDIUM_DLR_POSTGRESQL_PASSWORD}", application.getProperty("quarkus.datasource.dlr.password"));
        assertFalse(application.containsKey("sendium.dlr.persistence.enabled"));
        assertFalse(application.containsKey("quarkus.datasource.dlr.active"));
        assertEquals("10", application.getProperty("quarkus.datasource.dlr.jdbc.max-size"));
        var environment = mapper.readTree(files.get("sendium/environment.json"));
        assertEquals(deployment.database().password(), environment.path("SENDIUM_DLR_POSTGRESQL_PASSWORD").asText());
        assertTrue(files.get("sendium/conf/routingTable.conf").contains("smppserver.smpp:type:==:18"));
        assertTrue(files.get("sendium/conf/routingTable.conf").contains("provider::default:"));
        String published = files.get("public/effective-configuration.json");
        assertFalse(published.contains(credential.path("password").asText()));
        assertFalse(published.contains(harness.path("provider").path("password").asText()));
        assertFalse(published.contains(deployment.database().password()));
        assertFalse(published.contains(deployment.database().username()));
        var effective = mapper.readTree(published);
        assertEquals("postgresql", effective.path("dlrStorage").asText());
        assertEquals(dlr, effective.path("dlrRequested").asBoolean());
        assertEquals(dlr, mapper.readTree(files.get("harness/run-config.json")).path("dlrEnabled").asBoolean());
    }

    @Test
    void eachGenerationCreatesIndependentCredentialsWithoutPrintingSecrets() throws Exception {
        var first = generator.generate(workload("http", true), deployment());
        var second = generator.generate(workload("http", true), deployment());
        assertNotEquals(first.files().get("sendium/conf/credentials.yml"), second.files().get("sendium/conf/credentials.yml"));
        assertNotEquals(first.files().get("harness/runtime-settings.json"), second.files().get("harness/runtime-settings.json"));
        assertFalse(first.toString().contains("credentials.yml"));
        assertFalse(deployment().toString().contains(deployment().database().password()));
    }

    @ParameterizedTest
    @CsvSource({"http,true", "http,false", "smpp,true", "smpp,false"})
    void zeroWorkerTpsPreservesSendiumUnlimitedSemantics(String mode, boolean dlr) throws Exception {
        var config = new RunConfig(1, mode, 30, 0, 120, 600, 120, dlr, 5, 1000, 10000, 1000, 100000);
        var files = generator.generate(config, deployment()).files();
        assertEquals("0", properties(files.get("sendium/conf/smsg.properties")).getProperty("outSms.instance.provider.tps"));
        assertEquals(0, mapper.readTree(files.get("harness/run-config.json")).path("workerTps").asInt());
        assertEquals(30, mapper.readTree(files.get("harness/run-config.json")).path("offeredMessagesPerSecond").asDouble());
    }

    @Test
    void writerProtectsPrivateFilesAndRefusesExistingOutputOrEscapingPaths() throws Exception {
        Path output = directory.resolve("run");
        var generated = generator.generate(workload("http", true), deployment());
        generator.write(output, generated);
        assertEquals(generated.files().get("sendium/conf/credentials.yml"), Files.readString(output.resolve("sendium/conf/credentials.yml")));
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> generator.write(output, generated));
        assertThrows(IllegalArgumentException.class, () -> generator.write(directory.resolve("escape"),
                new BenchmarkConfigGenerator.Generated(Map.of("../escaped.txt", "bad"))));
        assertFalse(Files.exists(directory.resolve("escaped.txt")));
        if (output.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(output));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(output.resolve("sendium/environment.json")));
        }
    }

    @Test
    void invalidHostsCredentialsInJdbcUrlsAndInconsistentCapacityAreRejected() throws Exception {
        String fixture = Files.readString(Path.of("src/test/resources/deployment.json"));
        for (String invalid : new String[]{
                fixture.replace("jdbc:postgresql://127.0.0.1:5432/sendium_benchmark", "jdbc:postgresql://user:secret@127.0.0.1/db"),
                fixture.replace("jdbc:postgresql://127.0.0.1:5432/sendium_benchmark", "jdbc:postgresql://127.0.0.1/db?password=secret"),
                fixture.replace("jdbc:postgresql://127.0.0.1:5432/sendium_benchmark", "jdbc:postgresql://127.0.0.1/db?%70assword=secret"),
                fixture.replace("\"sendiumHost\": \"127.0.0.1\"", "\"sendiumHost\": \"bad/host\""),
                fixture.replace("\"poolMaxSize\": 10", "\"poolMaxSize\": 0")}) {
            assertThrows(Exception.class, () -> mapper.readValue(invalid, BenchmarkDeployment.class));
        }
        var tooManyRequests = new RunConfig(1, "smpp", 100, 100, 0, 1, 1, true, 0, 0, 1000, 1001, 1001);
        assertThrows(IllegalArgumentException.class, () -> generator.generate(tooManyRequests, deployment()));
        var excessiveDelay = new RunConfig(1, "http", 100, 100, 0, 1, 1, true, 30000, 0, 1000, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> generator.generate(excessiveDelay, deployment()));
    }

    @Test
    void commandLineGeneratesBundlesAndNeverOverwritesExistingRun() throws Exception {
        Path output = directory.resolve("cli-run");
        String[] args = {"generate-config", "src/test/resources/run-config.json", "src/test/resources/deployment.json", output.toString()};
        assertEquals(0, HarnessMain.run(args));
        assertTrue(Files.isRegularFile(output.resolve("public/effective-configuration.json")));
        String original = Files.readString(output.resolve("sendium/conf/credentials.yml"));
        assertEquals(2, HarnessMain.run(args));
        assertEquals(original, Files.readString(output.resolve("sendium/conf/credentials.yml")));
    }
}
