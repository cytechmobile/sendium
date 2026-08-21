package gr.cytech.sendium.core.worker;

import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Startup
@ApplicationScoped
@IfBuildProperty(name = "sendium.dlr.persistence.enabled", stringValue = "true", enableIfMissing = false)
public class ManagedDlrStorage implements DlrStorage {
    private static final String METRIC_NAME = "sendium.dlr.storage.operation";
    private static final String BACKEND = "postgresql";
    private static final String POSTGRESQL_PROBE_SQL = """
            SELECT 1
            FROM sendium_dlr.dlr_message
            WHERE FALSE
            """;

    @Inject
    @ConfigProperty(name = "quarkus.flyway.dlr.active", defaultValue = "false")
    boolean flywayActive;

    @Inject
    @ConfigProperty(name = "quarkus.flyway.dlr.migrate-at-start", defaultValue = "false")
    boolean flywayMigrateAtStart;

    @Inject
    @DataSource("dlr")
    InjectableInstance<AgroalDataSource> postgresqlDataSource;

    @Inject
    MeterRegistry meterRegistry;

    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    private DlrStorage delegate;
    private AgroalDataSource selectedPostgresqlDataSource;

    @PostConstruct
    void initialize() {
        boolean postgresqlActive = postgresqlDataSource.getHandle().getBean().isActive();
        if (!postgresqlActive || !flywayActive || !flywayMigrateAtStart) {
            throw new IllegalStateException(
                    "PostgreSQL DLR storage requires the active 'dlr' datasource and Flyway migration");
        }
        selectedPostgresqlDataSource = postgresqlDataSource.get();
        delegate = new PostgresqlDlrStorage(selectedPostgresqlDataSource);

        Gauge.builder("sendium.dlr.storage.selected", this, ignored -> 1.0)
                .description("Active Sendium DLR storage backend")
                .tag("backend", BACKEND)
                .strongReference(true)
                .register(meterRegistry);
    }

    String backend() {
        return BACKEND;
    }

    void verifyPostgresqlSchema() throws SQLException {
        if (selectedPostgresqlDataSource == null) {
            return;
        }
        try (Connection connection = selectedPostgresqlDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(POSTGRESQL_PROBE_SQL)) {
            statement.executeQuery();
        }
    }

    @Override
    public void saveInitialState(MessageState state) {
        timed("save_initial", () -> delegate.saveInitialState(state));
    }

    @Override
    public void saveInitialStates(List<MessageState> states) {
        timed("save_initial_batch", () -> delegate.saveInitialStates(states));
    }

    @Override
    public void linkProviderMessageId(String gatewayMessageId, String providerName, String providerMessageId) {
        timed("link_provider", () -> delegate.linkProviderMessageId(
                gatewayMessageId, providerName, providerMessageId));
    }

    @Override
    public Optional<MessageState> resolveDlr(String providerName, String providerMessageId,
                                             MessageState.MessageStatus status, int dlrState, String errorCode) {
        return timed("resolve", () -> delegate.resolveDlr(
                providerName, providerMessageId, status, dlrState, errorCode));
    }

    @Override
    public Optional<MessageState> getState(String gatewayMsgId) {
        return timed("get_state", () -> delegate.getState(gatewayMsgId));
    }

    @Override
    public List<MessageState> listPendingSmppDeliveries(String systemId) {
        return timed("list_pending_smpp", () -> delegate.listPendingSmppDeliveries(systemId));
    }

    @Override
    public List<MessageState> listDueHttpDeliveries(int limit) {
        return timed("list_due_http", () -> delegate.listDueHttpDeliveries(limit));
    }

    @Override
    public Optional<MessageState> startDeliveryAttempt(String gatewayMsgId,
                                                       MessageState.DeliveryChannel expectedChannel) {
        return timed("start_delivery", () -> delegate.startDeliveryAttempt(gatewayMsgId, expectedChannel));
    }

    @Override
    public boolean completeDelivery(String gatewayMsgId, int expectedAttempt) {
        return timed("complete_delivery", () -> delegate.completeDelivery(gatewayMsgId, expectedAttempt));
    }

    @Override
    public boolean retryDelivery(String gatewayMsgId, int expectedAttempt, String result, long nextAttemptAt) {
        return timed("retry_delivery", () -> delegate.retryDelivery(
                gatewayMsgId, expectedAttempt, result, nextAttemptAt));
    }

    @Override
    public boolean failDelivery(String gatewayMsgId, int expectedAttempt, String result) {
        return timed("fail_delivery", () -> delegate.failDelivery(gatewayMsgId, expectedAttempt, result));
    }

    @Override
    public boolean failInvalidDelivery(String gatewayMsgId, String result) {
        return timed("fail_invalid_delivery", () -> delegate.failInvalidDelivery(gatewayMsgId, result));
    }

    private <T> T timed(String operation, Supplier<T> action) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            T result = action.get();
            sample.stop(timer(operation, "success"));
            return result;
        } catch (RuntimeException | Error e) {
            sample.stop(timer(operation, "error"));
            throw e;
        }
    }

    private void timed(String operation, Runnable action) {
        timed(operation, () -> {
            action.run();
            return null;
        });
    }

    private Timer timer(String operation, String outcome) {
        return timers.computeIfAbsent(operation + '/' + outcome, ignored -> Timer.builder(METRIC_NAME)
                .description("Sendium DLR storage operation latency")
                .tags("backend", BACKEND, "operation", operation, "outcome", outcome)
                .register(meterRegistry));
    }
}
