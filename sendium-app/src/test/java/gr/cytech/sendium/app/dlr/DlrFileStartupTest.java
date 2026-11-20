package gr.cytech.sendium.app.dlr;

import gr.cytech.sendium.core.worker.DlrStorage;
import gr.cytech.sendium.core.worker.MessageState;
import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.flyway.FlywayDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.health.SmallRyeHealth;
import io.smallrye.health.SmallRyeHealthReporter;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DlrFileStartupTest {
    private static final Path ROOT = temporaryRoot();
    private static final Path DLR = ROOT.resolve("dlr");
    private static final Path SMS = ROOT.resolve("sms");

    @RegisterExtension
    static final QuarkusExtensionTest APP = DlrStartupSupport.application()
            .overrideConfigKey("sendium.dlr.persistence.enabled", "true")
            .overrideConfigKey("sendium.dlr.storage.backend", "file")
            .overrideConfigKey("sendium.dlr.storage.file.path", DLR.toString())
            // Sibling SMS namespace on the same volume; only configuration, the spool beans are isolated here.
            .overrideConfigKey("sendium.message.pending.backend", "file")
            .overrideConfigKey("sendium.message.pending.path", SMS.toString());

    @Inject DlrStorage storage;
    @Inject MeterRegistry metrics;
    @Inject SmallRyeHealthReporter health;
    @Inject @DataSource("dlr") InjectableInstance<AgroalDataSource> datasource;
    @Inject @FlywayDataSource("dlr") InjectableInstance<Flyway> flyway;

    @Test
    @Order(1)
    void startsWithoutDatasourceOrMigration() {
        if (!datasource.isUnsatisfied()) {
            assertThat(datasource.getHandle().getBean().isActive()).isFalse();
        }
        if (!flyway.isUnsatisfied()) {
            assertThat(flyway.getHandle().getBean().isActive()).isFalse();
        }
        MessageState state = accepted();
        assertThat(storage.getState(state.getGatewayMsgId())).isPresent();
        assertThat(DLR.resolve("dlr.db")).isRegularFile();
        assertThat(readiness().getPayload().toString()).contains("\"backend\":\"file\"").contains("UP");
    }

    @Test
    @Order(2)
    void requiresIndependentDlrOwnership() throws IOException {
        Files.createDirectories(SMS);
        Path marker = Files.writeString(SMS.resolve("marker"), "sms");
        accepted();
        try (var files = Files.list(DLR)) {
            assertThat(files.map(path -> path.getFileName().toString())).contains("dlr.db")
                    .noneMatch(name -> name.equals("marker"));
        }
        assertThat(Files.readString(marker)).isEqualTo("sms");
    }

    @Test
    @Order(3)
    void reportsFileMetricsWithoutUnboundedLabels() {
        accepted();
        assertThat(metrics.get("sendium.dlr.storage.selected").tag("backend", "file").gauge().value()).isEqualTo(1);
        assertThat(metrics.get("sendium.dlr.storage.operation").tag("backend", "file")
                .tag("operation", "record_provider_accepted").tag("outcome", "success").timer().count()).isPositive();
        assertThat(metrics.get("sendium.dlr.storage.messages").gauges()).hasSize(3).allSatisfy(gauge ->
                assertThat(tagKeys(gauge)).containsExactlyInAnyOrder("backend", "state"));
        for (Meter meter : metrics.getMeters()) {
            if (meter.getId().getName().startsWith("sendium.dlr.storage")) {
                assertThat(meter.getId().getTag("backend")).isEqualTo("file");
                assertThat(meter.getId().getTags()).extracting(Tag::getValue)
                        .noneMatch(value -> value.contains(ROOT.toString()) || value.contains("-"));
            }
        }
    }

    @Test
    @Order(4)
    void fileFailureAffectsOnlySanitizedReadiness() throws Exception {
        // Another process holds an exclusive SQLite lock, so the next durable write cannot publish.
        try (Connection foreign = DriverManager.getConnection("jdbc:sqlite:" + DLR.resolve("dlr.db"));
             Statement statement = foreign.createStatement()) {
            statement.execute("BEGIN EXCLUSIVE");
            assertThatThrownBy(this::accepted).isInstanceOf(RuntimeException.class);
            statement.execute("ROLLBACK");
        }
        SmallRyeHealth readiness = readiness();
        String payload = readiness.getPayload().toString();
        assertThat(readiness.isDown()).isTrue();
        assertThat(payload).contains("\"backend\":\"file\"").contains("\"reason\":\"unavailable\"")
                .doesNotContain(ROOT.toString()).doesNotContain(ROOT.toString().replace("\\", "\\\\"))
                .doesNotContain("callback.test").doesNotContain("SQLITE").doesNotContain("Exception");
        assertThat(health.getLiveness().isDown()).isFalse();
    }

    private MessageState accepted() {
        MessageState state = new MessageState(UUID.randomUUID().toString(), "account", "system",
                "source", "destination", "https://user:secret@callback.test/dlr");
        state.setDeliveryChannel(MessageState.DeliveryChannel.HTTP);
        storage.recordProviderAccepted(state, "provider", UUID.randomUUID().toString());
        return state;
    }

    private SmallRyeHealth readiness() {
        return health.getReadiness();
    }

    private static Set<String> tagKeys(Meter meter) {
        return meter.getId().getTags().stream().map(Tag::getKey).collect(Collectors.toSet());
    }

    // The class is loaded by JUnit and again by Quarkus; a system property keeps one root for both.
    private static synchronized Path temporaryRoot() {
        String key = DlrFileStartupTest.class.getName() + ".root";
        String existing = System.getProperty(key);
        if (existing != null) {
            return Path.of(existing);
        }
        try {
            Path created = Files.createTempDirectory("sendium-dlr-file-");
            System.setProperty(key, created.toString());
            return created;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
