package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;
import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

@Startup
@ApplicationScoped
public class ConfiguredDlrStorage implements DlrStorage {
    private static final String METRIC_NAME = "sendium.dlr.storage.operation";
    private static final String POSTGRESQL_PROBE_SQL = """
            SELECT 1
            FROM sendium_dlr.tracked_message
            WHERE FALSE
            """;

    @Inject
    @ConfigProperty(name = "sendium.dlr.storage", defaultValue = "mvstore")
    String configuredBackend;

    @Inject
    @ConfigProperty(name = "quarkus.flyway.dlr.active", defaultValue = "false")
    boolean flywayActive;

    @Inject
    @ConfigProperty(name = "quarkus.flyway.dlr.migrate-at-start", defaultValue = "false")
    boolean flywayMigrateAtStart;

    @Inject
    Instance<MvStoreDlrStorage> mvStoreStorage;

    @Inject
    @DataSource("dlr")
    InjectableInstance<AgroalDataSource> postgresqlDataSource;

    @Inject
    MeterRegistry meterRegistry;

    private DlrStorage delegate;
    private AgroalDataSource selectedPostgresqlDataSource;
    private String backend;

    @PostConstruct
    void initialize() {
        backend = configuredBackend.strip().toLowerCase(Locale.ROOT);
        boolean postgresqlActive = postgresqlDataSource.getHandle().getBean().isActive();
        delegate = switch (backend) {
            case "mvstore" -> {
                if (postgresqlActive || flywayActive || flywayMigrateAtStart) {
                    throw new IllegalStateException(
                            "The DLR PostgreSQL datasource and Flyway must be inactive when MVStore is selected");
                }
                yield mvStoreStorage.get();
            }
            case "postgresql" -> {
                if (!postgresqlActive || !flywayActive || !flywayMigrateAtStart) {
                    throw new IllegalStateException(
                            "PostgreSQL DLR storage requires the active 'dlr' datasource and Flyway migration");
                }
                selectedPostgresqlDataSource = postgresqlDataSource.get();
                yield new PostgresqlDlrStorage(selectedPostgresqlDataSource);
            }
            default -> throw new IllegalStateException("Unsupported DLR storage backend: " + backend);
        };

        Gauge.builder("sendium.dlr.storage.selected", this, ignored -> 1.0)
                .description("Selected Sendium DLR storage backend")
                .tag("backend", backend)
                .strongReference(true)
                .register(meterRegistry);
    }

    String backend() {
        return backend;
    }

    String mode() {
        if (delegate instanceof MvStoreDlrStorage mvStore) {
            return mvStore.isPersistent() ? "persistent" : "memory";
        }
        return "persistent";
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
    public void linkOperatorId(String gatewayMsgId, String operatorMsgId) {
        timed("link_operator", () -> delegate.linkOperatorId(gatewayMsgId, operatorMsgId));
    }

    @Override
    public Optional<MessageState> resolveAndRemoveDlr(String operatorMsgId, MessageState.MessageStatus status) {
        return timed("resolve", () -> delegate.resolveAndRemoveDlr(operatorMsgId, status));
    }

    @Override
    public Optional<MessageState> getState(String gatewayMsgId) {
        return timed("get_state", () -> delegate.getState(gatewayMsgId));
    }

    @Override
    public boolean markAsFailed(String gatewayMsgId) {
        return timed("mark_failed", () -> delegate.markAsFailed(gatewayMsgId));
    }

    @Override
    public boolean saveUnpushedDlr(StandardMessage message) {
        return timed("save_unpushed", () -> delegate.saveUnpushedDlr(message));
    }

    @Override
    public List<StandardMessage> getUnpushedDlrs(String systemId) {
        return timed("get_unpushed", () -> delegate.getUnpushedDlrs(systemId));
    }

    @Override
    public List<StandardMessage> claimUnpushedDlrs(String systemId) {
        return timed("claim_unpushed", () -> delegate.claimUnpushedDlrs(systemId));
    }

    @Override
    public boolean removeUnpushedDlr(StandardMessage message) {
        return timed("remove_unpushed", () -> delegate.removeUnpushedDlr(message));
    }

    @Override
    public void releaseUnpushedDlrClaim(StandardMessage message) {
        timed("release_claim", () -> delegate.releaseUnpushedDlrClaim(message));
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
        return Timer.builder(METRIC_NAME)
                .description("Sendium DLR storage operation latency")
                .tags("backend", backend, "operation", operation, "outcome", outcome)
                .register(meterRegistry);
    }
}
