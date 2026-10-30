package gr.cytech.sendium.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkTargetTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void absentTargetPreservesSendiumAndUnknownOrNullTargetsAreRejected() throws Exception {
        assertEquals(BenchmarkTarget.SENDIUM, BenchmarkTarget.from(mapper.readTree("{}")));
        for (String value : List.of("null", "\"other\"", "3", "\"Kannel\"")) {
            assertThrows(IllegalArgumentException.class,
                    () -> BenchmarkTarget.from(mapper.readTree("{\"benchmarkTarget\":" + value + "}")));
        }
    }

    @Test
    void readinessRequiresActualTargetWireSemanticsAndSendiumStorageHealth() throws Exception {
        assertTrue(BenchmarkTarget.KANNEL.ready(403, "Authorization failed for sendsms\n", mapper));
        assertFalse(BenchmarkTarget.KANNEL.ready(403, "Forbidden", mapper));
        assertFalse(BenchmarkTarget.KANNEL.ready(200, "Authorization failed for sendsms", mapper));
        String healthy = """
                {"status":"UP","checks":[{"name":"sendium-dlr-storage","status":"UP","data":{"backend":"postgresql"}}]}
                """;
        assertTrue(BenchmarkTarget.SENDIUM.ready(200, healthy, mapper));
        assertFalse(BenchmarkTarget.SENDIUM.ready(200, healthy.replace("postgresql", "internal"), mapper));
        assertFalse(BenchmarkTarget.SENDIUM.ready(200, "{\"status\":\"UP\"}", mapper));
        assertFalse(BenchmarkTarget.SENDIUM.ready(403, "Authorization failed for sendsms", mapper));
    }

    @Test
    void kannelCannotBorrowSendiumCommitHeapOrInfrastructureIdentity() {
        var context = KannelFixtures.context("identity-test");
        for (String field : List.of("runtime", "dlrStorage", "messageStore", "source")) {
            var application = context.application().deepCopy(); application.remove(field);
            assertThrows(IllegalArgumentException.class, () -> new ResultReport.Context(context.provenance(),
                    application, context.infrastructure(), List.of()));
        }
        var application = context.application().deepCopy(); application.withArray("jvmArguments").add("-Xmx512m");
        assertThrows(IllegalArgumentException.class, () -> new ResultReport.Context(context.provenance(),
                application, context.infrastructure(), List.of()));
        var infrastructure = context.infrastructure().deepCopy(); infrastructure.putObject("sendium");
        assertThrows(IllegalArgumentException.class, () -> new ResultReport.Context(context.provenance(),
                context.application(), infrastructure, List.of()));
        var provenance = new ResultReport.Provenance("test", "a".repeat(40), "b".repeat(40), null, "test", List.of());
        assertThrows(IllegalArgumentException.class, () -> new ResultReport.Context(provenance,
                context.application(), context.infrastructure(), List.of()));
        var sendium = mapper.createObjectNode().put("dlrStorage", "postgresql");
        sendium.putArray("jvmArguments"); sendium.putObject("connections");
        assertThrows(IllegalArgumentException.class, () -> new ResultReport.Context(context.provenance(),
                sendium, context.infrastructure(), List.of()));
    }

    @Test
    void postgresBackendRequiresExplicitProvisioningAndVerifiedTlsConfiguration() {
        var context = KannelFixtures.context("postgres-identity");
        context.application().put("dlrStorage", "postgresql");
        assertThrows(IllegalArgumentException.class, () -> new ResultReport.Context(context.provenance(),
                context.application(), context.infrastructure(), List.of()));
        context.infrastructure().withObject("/database").put("engine", "postgresql").put("provisioned", true).put("sslMode", "verify-full");
        assertDoesNotThrow(() -> new ResultReport.Context(context.provenance(), context.application(), context.infrastructure(), List.of()));
        context.infrastructure().withObject("/database").put("sslMode", "prefer");
        assertThrows(IllegalArgumentException.class, () -> new ResultReport.Context(context.provenance(),
                context.application(), context.infrastructure(), List.of()));
    }
}
