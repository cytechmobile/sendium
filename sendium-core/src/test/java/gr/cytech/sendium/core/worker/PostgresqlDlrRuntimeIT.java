package gr.cytech.sendium.core.worker;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.Address;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import gr.cytech.sendium.routing.OutgoingWorkerManager;
import gr.cytech.sendium.routing.StandardOutgoingWorkerHandler;
import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.flyway.FlywayDataSource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import utils.CaptorWorker;

import io.netty.channel.nio.NioEventLoopGroup;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@QuarkusTestResource(value = PostgresqlDlrQuarkusTestResource.class, restrictToAnnotatedClass = true)
class PostgresqlDlrRuntimeIT {
    @Inject
    DlrStorage storage;

    @Inject
    ManagedDlrStorage managedStorage;

    @Inject
    @DataSource("dlr")
    InjectableInstance<AgroalDataSource> dataSource;

    @Inject
    @FlywayDataSource("dlr")
    InjectableInstance<Flyway> flyway;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    OutgoingWorkerManager outgoingWorkerManager;

    @Test
    void wiresPoolMigrationStorageHealthAndMetrics() throws SQLException {
        long successfulSavesBefore = metricCount("save_initial", "success");
        assertThat(storage).isSameAs(managedStorage);
        assertThat(managedStorage.backend()).isEqualTo("postgresql");
        assertThat(dataSource.getHandle().getBean().isActive()).isTrue();
        assertThat(flyway.getHandle().getBean().isActive()).isTrue();
        assertThat(flyway.get().info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(flywayHistoryCount()).isOne();

        MessageState state = new MessageState(UUID.randomUUID().toString(), "account", "system",
                "source", "destination", null);
        storage.saveInitialState(state);
        assertThat(storage.getState(state.getGatewayMsgId())).isPresent();

        given()
                .when().get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.find { it.name == 'sendium-dlr-storage' }.data.backend",
                        equalTo("postgresql"));

        assertThat(metricCount("save_initial", "success")).isEqualTo(successfulSavesBefore + 1);
        assertThat(meterRegistry.find("sendium.dlr.storage.selected")
                .tag("backend", "postgresql").gauge().value()).isEqualTo(1.0);
        assertThat(meterRegistry.getMeters())
                .extracting(meter -> meter.getId().getName())
                .anyMatch(name -> name.startsWith("agroal"));
    }

    @Test
    void databaseOutageRejectsHttpAndRetriesAcceptedSmppBeforeRouting() throws Exception {
        StandardOutgoingWorkerHandler outgoingWorkerHandler = (StandardOutgoingWorkerHandler) outgoingWorkerManager;
        CaptorWorker captorWorker = (CaptorWorker) outgoingWorkerHandler.getWorkers().get("captorTest");
        captorWorker.captures.clear();
        try (DownstreamSmppClient smppClient = new DownstreamSmppClient(
                PostgresqlDlrQuarkusTestResource.getSmppPort())) {
            smppClient.start();
            PostgresqlDlrQuarkusTestResource.pausePostgresql();
            SubmitSmResp acceptedSmpp;
            try {
                given()
                        .queryParam("username", "test2")
                        .queryParam("password", "123qwe")
                        .queryParam("from", "Sender")
                        .queryParam("to", "306910000000")
                        .queryParam("text", "database outage http")
                        .when().get("/sendsms")
                        .then()
                        .statusCode(503)
                        .body(equalTo("Temporal failure, try again later."));

                acceptedSmpp = smppClient.sendSms(
                        "Sender", "306910000001", "database outage smpp");
                assertThat(acceptedSmpp.getCommandStatus()).isEqualTo(SmppConstants.STATUS_OK);
                assertThat(acceptedSmpp.getMessageId()).isNotBlank();
                assertThat(captorWorker.captures).isEmpty();
                assertThat(managedStorage.backend()).isEqualTo("postgresql");

                given()
                        .when().get("/q/health/ready")
                        .then()
                        .statusCode(503)
                        .body("status", equalTo("DOWN"))
                        .body("checks.find { it.name == 'sendium-dlr-storage' }.data.reason",
                                equalTo("unavailable"));
                assertThat(metricCount("save_initial", "error")).isGreaterThanOrEqualTo(1);
                awaitMetric("save_initial_batch", "error");
            } finally {
                PostgresqlDlrQuarkusTestResource.resumePostgresql();
                awaitPostgresqlRecovery();
            }

            var retriedSmpp = captorWorker.captures.poll(10, TimeUnit.SECONDS);
            assertThat(retriedSmpp).isNotNull();
            assertThat(retriedSmpp.body).isEqualTo("database outage smpp");
            assertThat(retriedSmpp.serial).isEqualTo(acceptedSmpp.getMessageId());
            assertThat(storage.getState(acceptedSmpp.getMessageId())).isPresent();

            String httpGatewayId = submitHttpAfterRecovery();
            SubmitSmResp recoveredSmpp = smppClient.sendSms(
                    "Sender", "306910000003", "database recovered smpp");
            assertThat(recoveredSmpp.getCommandStatus()).isEqualTo(SmppConstants.STATUS_OK);
            assertThat(recoveredSmpp.getMessageId()).isNotBlank();
            assertThat(storage.getState(httpGatewayId)).isPresent();
            var firstRouted = captorWorker.captures.poll(5, TimeUnit.SECONDS);
            var secondRouted = captorWorker.captures.poll(5, TimeUnit.SECONDS);
            assertThat(firstRouted).isNotNull();
            assertThat(secondRouted).isNotNull();
            assertThat(List.of(firstRouted.body, secondRouted.body))
                    .containsExactlyInAnyOrder("database recovered http", "database recovered smpp");
            assertThat(storage.getState(recoveredSmpp.getMessageId())).isPresent();

            given()
                    .when().get("/q/health/ready")
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("UP"));
        }
    }

    private int flywayHistoryCount() throws SQLException {
        try (Connection connection = dataSource.get().getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM sendium_dlr.flyway_schema_history
                     WHERE success AND version = '1'
                     """)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private long metricCount(String operation, String outcome) {
        var timer = meterRegistry.find("sendium.dlr.storage.operation")
                .tags("backend", "postgresql", "operation", operation, "outcome", outcome)
                .timer();
        return timer == null ? 0 : timer.count();
    }

    private void awaitMetric(String operation, String outcome) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (metricCount(operation, outcome) > 0) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Metric was not recorded for " + operation + "/" + outcome);
    }

    private void awaitPostgresqlRecovery() throws InterruptedException {
        DlrStorageException lastFailure = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                storage.getState(UUID.randomUUID().toString());
                return;
            } catch (DlrStorageException e) {
                lastFailure = e;
                Thread.sleep(250);
            }
        }
        throw new AssertionError("PostgreSQL storage did not recover", lastFailure);
    }

    private String submitHttpAfterRecovery() {
        return given()
                .queryParam("username", "test2")
                .queryParam("password", "123qwe")
                .queryParam("from", "Sender")
                .queryParam("to", "306910000002")
                .queryParam("text", "database recovered http")
                .when().get("/sendsms")
                .then()
                .statusCode(202)
                .extract().asString();
    }

    private static class DownstreamSmppClient implements AutoCloseable {
        private final int port;
        private final DefaultSmppClient client;
        private SmppSession session;

        DownstreamSmppClient(int port) {
            this.port = port;
            this.client = new DefaultSmppClient(new NioEventLoopGroup(
                    1, new ThreadFactoryBuilder().setDaemon(true).setNameFormat("postgresql-smpp-client-%d").build()));
        }

        void start() throws Exception {
            SmppSessionConfiguration configuration = new SmppSessionConfiguration(
                    SmppBindType.TRANSCEIVER, "test1", "123qwe");
            configuration.setHost("127.0.0.1");
            configuration.setPort(port);
            configuration.setWindowSize(10);
            Exception lastFailure = null;
            for (int attempt = 0; attempt < 20; attempt++) {
                try {
                    session = client.bind(configuration, new DefaultSmppSessionHandler());
                    return;
                } catch (Exception e) {
                    lastFailure = e;
                    Thread.sleep(250);
                }
            }
            throw new IllegalStateException("Could not bind to the Sendium SMPP test server", lastFailure);
        }

        SubmitSmResp sendSms(String from, String to, String text) throws Exception {
            SubmitSm submit = new SubmitSm();
            submit.setSourceAddress(new Address((byte) 0, (byte) 0, from));
            submit.setDestAddress(new Address((byte) 0, (byte) 0, to));
            submit.setRegisteredDelivery(SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED);
            submit.setDataCoding(SmppConstants.DATA_CODING_DEFAULT);
            submit.setShortMessage(text.getBytes(StandardCharsets.UTF_8));
            return session.submit(submit, 10_000);
        }

        @Override
        public void close() {
            if (session != null) {
                session.destroy();
            }
            client.destroy(0, 0);
        }
    }
}
