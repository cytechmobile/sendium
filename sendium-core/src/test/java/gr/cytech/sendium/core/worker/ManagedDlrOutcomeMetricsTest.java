package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;
import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.arc.InjectableInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

class ManagedDlrOutcomeMetricsTest {
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final MessageState state = new MessageState("gateway", "account", "system", "from", "to", "https://callback.test");

    @AfterEach
    void close() {
        metrics.close();
    }

    @Test
    void synchronousOutcomesRequireACommittedTransitionAndExcludeSubmissionRejection() {
        try (var backends = mockConstruction(PostgresqlDlrStorage.class)) {
            var storage = storage();
            var backend = backends.constructed().getFirst();
            when(backend.resolveDlr(anyString(), anyString(), any(), anyInt(), anyString()))
                    .thenReturn(Optional.of(state)).thenReturn(Optional.empty())
                    .thenThrow(new DlrStorageException("commit failed"));
            storage.resolveDlr("provider", "id", MessageState.MessageStatus.DELIVERED, StandardMessage.DLR_STAT_DELIVRD, "0");
            storage.resolveDlr("provider", "id", MessageState.MessageStatus.DELIVERED, StandardMessage.DLR_STAT_DELIVRD, "0");
            assertThatThrownBy(() -> storage.resolveDlr("provider", "id", MessageState.MessageStatus.DELIVERED,
                    StandardMessage.DLR_STAT_DELIVRD, "0")).isInstanceOf(DlrStorageException.class);
            when(backend.recordProviderRejected(any(), anyString(), anyString(), anyInt(), anyString()))
                    .thenReturn(Optional.of(state));
            storage.recordProviderRejected(state, "provider", "id", StandardMessage.DLR_STAT_FAILED, "1");
            storage.recordProviderAccepted(state, "provider", "id");
            assertThat(outcomes("delivered")).isEqualTo(1);
            assertThat(outcomes("failed")).isZero();
        }
    }

    @Test
    void batchCountsOnlyNewReceiptTransitionsAfterSuccessfulReturnNotRollbackOrDuplicates() {
        try (var backends = mockConstruction(PostgresqlDlrStorage.class)) {
            var storage = storage();
            var backend = backends.constructed().getFirst();
            List<ProviderDlrEvent> events = List.of(
                    new ProviderDlrEvent.Accepted(state, "provider", "accepted"),
                    new ProviderDlrEvent.Rejected(state, "provider", "rejected", StandardMessage.DLR_STAT_FAILED, "1"),
                    receipt("delivered", StandardMessage.DLR_STAT_DELIVRD),
                    receipt("duplicate", StandardMessage.DLR_STAT_DELIVRD),
                    receipt("expired", StandardMessage.DLR_STAT_EXPIRED),
                    receipt("failed", StandardMessage.DLR_STAT_UNDELIV),
                    receipt("failed-operation", StandardMessage.DLR_STAT_UNDELIV));
            List<ProviderDlrEvent.Result> results = List.of(
                    ProviderDlrEvent.Result.success(Optional.empty()),
                    ProviderDlrEvent.Result.success(Optional.of(state)),
                    ProviderDlrEvent.Result.success(Optional.of(state)),
                    ProviderDlrEvent.Result.success(Optional.empty()),
                    ProviderDlrEvent.Result.success(Optional.of(state)),
                    ProviderDlrEvent.Result.success(Optional.of(state)),
                    new ProviderDlrEvent.Result(Optional.empty(), new DlrStorageException("event failed")));
            when(backend.applyProviderBatch(events)).thenThrow(new DlrStorageException("rollback")).thenReturn(results);
            assertThatThrownBy(() -> storage.applyProviderBatch(events)).isInstanceOf(DlrStorageException.class);
            assertThat(outcomes("delivered")).isZero();
            assertThat(outcomes("expired")).isZero();
            storage.applyProviderBatch(events);
            assertThat(outcomes("delivered")).isEqualTo(1);
            assertThat(outcomes("expired")).isEqualTo(1);
            assertThat(outcomes("failed")).isEqualTo(1);
        }
    }

    private ProviderDlrEvent.Receipt receipt(String id, int dlrState) {
        return new ProviderDlrEvent.Receipt("provider", id, dlrState == StandardMessage.DLR_STAT_DELIVRD ?
                MessageState.MessageStatus.DELIVERED : MessageState.MessageStatus.FAILED, dlrState, "0");
    }

    @SuppressWarnings("unchecked")
    private ManagedDlrStorage storage() {
        var datasource = mock(InjectableInstance.class, RETURNS_DEEP_STUBS);
        when(datasource.getHandle().getBean().isActive()).thenReturn(true);
        when(datasource.get()).thenReturn(mock(AgroalDataSource.class));
        var storage = new ManagedDlrStorage();
        storage.postgresqlDataSource = datasource;
        storage.meterRegistry = metrics;
        storage.flywayActive = true;
        storage.flywayMigrateAtStart = true;
        storage.deliveryClaimDuration = Duration.ofMinutes(5);
        storage.initialize();
        return storage;
    }

    private double outcomes(String outcome) {
        var counter = metrics.find("sendium.dlr.outcomes").tag("provider", "provider").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }
}
