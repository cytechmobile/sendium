package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.DlrReturnMetadata;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundDlrPolicy;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.arc.InjectableInstance;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class DisabledDlrProcessingTest {
    @Test
    void disabledModeDoesNotQueueCorrelationWork() throws Exception {
        var storage = selected("disabled");
        try (var ignored = (AutoCloseable) storage::close;
             var processor = new ProviderDlrProcessor(storage, storage.meterRegistry, 10, Duration.ofMillis(1), 10)) {
            var service = new DlrService();
            service.storage = storage;
            service.processor = processor;
            var state = MemoryDlrTestSupport.state(MessageState.DeliveryChannel.HTTP);
            assertThatThrownBy(() -> service.recordProviderAcceptedAsync(state, "P", "id").toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class).hasCauseInstanceOf(DlrStorageException.class);
            assertThatThrownBy(() -> service.recordProviderRejectedAsync(state, "P", "id", 2, "1").toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class).hasCauseInstanceOf(DlrStorageException.class);
            assertThatThrownBy(() -> processor.submit(new ProviderDlrEvent.Accepted(state, "P", "id")).toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class).hasCauseInstanceOf(DlrStorageException.class);
            assertThat(service.resolveDlrAsync("P", "id", 1, "0").toCompletableFuture().join()).isEmpty();
            assertThat(processor.submit(new ProviderDlrEvent.Receipt("P", "id", MessageState.MessageStatus.DELIVERED, 1, "0"))
                    .toCompletableFuture().join()).isEmpty();
            assertThatThrownBy(() -> storage.recordProviderAccepted(state, "P", "id")).isInstanceOf(DlrStorageException.class);
            assertThatThrownBy(() -> storage.completeDelivery(state.getGatewayMsgId(), 1)).isInstanceOf(DlrStorageException.class);
            assertThat(storage.meterRegistry.find("sendium.dlr.batch.pending").gauge()).isNull();
            verify(storage, never()).applyProviderBatch(org.mockito.ArgumentMatchers.any());
            var client = mock(HttpClient.class);
            var observed = spy(service);
            new ForwardDlrService(observed, client).dispatchDueDeliveries();
            verify(observed, never()).claimDueHttpDeliveries(org.mockito.ArgumentMatchers.anyInt());
            verifyNoInteractions(client);
            var callbacks = new ForwardDlrService(service);
            var httpClient = ForwardDlrService.class.getDeclaredField("httpClient");
            httpClient.setAccessible(true);
            assertThat(httpClient.get(callbacks)).isNull();
        }
    }

    @Test
    void disabledPolicyPreservesRequestedMetadataAndRejectsBeforeAcceptance() throws Exception {
        var storage = selected("disabled");
        try (var ignored = (AutoCloseable) storage::close) {
            var policy = policy(storage);
            var source = new SourceId(UUID.randomUUID());
            var message = new StandardMessage();
            message.dlrReturnMetadata = DlrReturnMetadata.smpp("account", "system", "sender", "recipient");
            assertThatThrownBy(() -> policy.reserveAdmission(source, message))
                    .isInstanceOfSatisfying(OutboundStorageException.class, failure ->
                            assertThat(failure.reason()).isEqualTo(OutboundStorageException.Reason.UNSUPPORTED));
            assertThatThrownBy(() -> policy.prepareProvider(source.value().toString(), Set.of(source)))
                    .isInstanceOfSatisfying(OutboundDlrPolicy.Blocked.class, failure ->
                            assertThat(failure.blockReason()).isEqualTo(OutboundDlrPolicy.BlockReason.DISABLED));
            assertThat(message.dlrReturnMetadata).isNotNull();
            assertThat(policy.reserveAdmission(source, new StandardMessage())).isSameAs(OutboundDlrPolicy.Reservation.UNCHANGED);
        }
    }

    @SuppressWarnings("unchecked")
    public static ManagedDlrStorage selected(String backend) {
        return selected(backend, 100_000);
    }

    @SuppressWarnings("unchecked")
    public static ManagedDlrStorage selected(String backend, int maximum) {
        var storage = spy(new ManagedDlrStorage());
        storage.selectedBackend = backend;
        storage.postgresqlDataSource = mock(InjectableInstance.class, RETURNS_DEEP_STUBS);
        when(storage.postgresqlDataSource.getHandle().getBean().isActive()).thenReturn(false);
        storage.meterRegistry = new SimpleMeterRegistry();
        storage.deliveryClaimDuration = Duration.ofMinutes(5);
        try {
            var field = ManagedDlrStorage.class.getDeclaredField("memoryMaximum");
            field.setAccessible(true);
            field.set(storage, maximum);
        } catch (NoSuchFieldException beforeActivation) {
            // The baseline rejects memory/disabled during initialize, rather than failing test compilation.
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
        storage.initialize();
        return storage;
    }

    /** Baseline tests compile against old entry points and fail on the unsupported selected mode first. */
    public static OutboundDlrPolicy policy(ManagedDlrStorage storage) {
        try {
            return (OutboundDlrPolicy) ManagedDlrStorage.class.getMethod("outboundDlrPolicy").invoke(storage);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Selected mode must expose its admission/provider policy", failure);
        }
    }

    public static AutoCloseable lifetime(ManagedDlrStorage storage) {
        return () -> {
            storage.close();
            storage.meterRegistry.close();
        };
    }
}
