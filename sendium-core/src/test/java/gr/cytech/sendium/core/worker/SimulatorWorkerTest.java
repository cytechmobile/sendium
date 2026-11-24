package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.conf.SendiumConfigurationProvider;
import gr.cytech.sendium.core.message.DlrReturnMetadata;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundDlrPolicy;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.queue.Queue;
import gr.cytech.sendium.core.smpp.client.ProviderRequestMetrics;
import gr.cytech.sendium.external.WorkerResourceProvider;
import gr.cytech.sendium.routing.RoutingLookupResult;
import gr.cytech.sendium.routing.StandardOutboundDispatch;
import gr.cytech.sendium.routing.StandardRoutingManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import utils.OutboundIngressFixture;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SimulatorWorkerTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void postFilterDlrGuardPreventsSimulatorEffects(boolean disabled) throws Exception {
        var tracker = tracker();
        var worker = worker("delivered", "true", tracker);
        var owner = new MemoryDlrReservations(1);
        var occupied = new SourceId(UUID.randomUUID());
        owner.reserveAdmission(occupied, message());
        var candidate = message();
        candidate.dlrReturnMetadata = null;
        var source = new SourceId(UUID.fromString(candidate.serial));
        worker.preparation = prepared -> prepared.dlrReturnMetadata = message().dlrReturnMetadata;
        OutboundDlrPolicy policy = disabled ? OutboundDlrPolicy.DISABLED : owner;

        assertThatThrownBy(() -> worker.submitPrepared(candidate, (payload, router) -> null, false, prepared -> {
            assertThat(prepared.dlrReturnMetadata).isNotNull();
            policy.prepareProvider(prepared.serial, Set.of(source));
        })).isInstanceOf(OutboundDlrPolicy.Blocked.class);
        verify(tracker, never()).handoffProviderAccepted(any(), any(), any());
        verify(tracker, never()).createAndEnqueueDlrAsync(anyInt(), any(), any(), any(), any(), any(), anyInt(), any(), any());
        assertThat(candidate.extrid).isNull();
        assertThat(owner.usedCapacity()).isEqualTo(1);

        if (!disabled) {
            owner.releaseOutbound(Set.of(occupied));
            worker.submitPrepared(candidate, (payload, router) -> null, true,
                    prepared -> owner.prepareProvider(prepared.serial, Set.of(source)))
                    .completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            verify(tracker).handoffProviderAccepted(any(), any(), any());
            assertThat(owner.usedCapacity()).isEqualTo(1);
        }
    }

    @Test
    void providersWithoutPostFilterProtectionFailExplicitly() {
        OutboundProvider<StandardMessage> unsupported = new OutboundProvider<>() {
            public String getFullName() { return "unsupported"; }
            public boolean isKeepOnRunning() { return true; }
            public boolean isPause() { return false; }
            public int getThreadCount() { return 1; }
            public Submission submitPrepared(StandardMessage message, Retry<StandardMessage> retry, boolean retryAttempt) {
                throw new AssertionError("Unguarded provider submission must not be used");
            }
        };
        assertThatThrownBy(() -> unsupported.submitPrepared(message(), (payload, router) -> null, false, ignored -> { }))
                .hasMessageContaining("providerDoesNotProtectPreparation");
    }

    @Test
    void coordinatedDispatchRetainsSourceUntilReceiptHandoffFinishes() throws Exception {
        var tracker = tracker();
        var receipt = new CompletableFuture<Void>();
        when(tracker.createAndEnqueueDlrAsync(anyInt(), any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(receipt);
        var worker = worker("delivered", "true", tracker);
        try (var fixture = new OutboundIngressFixture(2)) {
            var routing = mock(StandardRoutingManager.class);
            when(routing.lookupForLifecycle(any())).thenReturn(new RoutingLookupResult(List.of(worker), true));
            try (var dispatch = new StandardOutboundDispatch(fixture.coordinator, routing)) {
                var message = message();
                var source = new gr.cytech.sendium.core.outbound.OutboundWork.SourceId(UUID.fromString(message.serial));
                fixture.coordinator.accept(source, message);
                fixture.coordinator.selectToRouter(1);
                dispatch.routeSelected(fixture.coordinator.takeForRouting(Duration.ZERO).orElseThrow());
                var execution = dispatch.submitToProvider(
                        dispatch.takeForProvider(worker.getFullName(), Duration.ZERO).orElseThrow(), worker);
                try {
                    verify(tracker, timeout(5000)).createAndEnqueueDlrAsync(
                            anyInt(), any(), any(), any(), any(), any(), anyInt(), any(), any());
                    assertThat(fixture.pending.find(source)).isPresent();
                    assertThat(execution.isCompletedSuccessfully()).isFalse();
                } finally {
                    receipt.complete(null);
                    execution.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                }
                assertThat(fixture.pending.find(source)).isEmpty();
            }
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"delivered", "failed", "expired"})
    void acceptancePrecedesConfiguredReceipt(String outcome) throws Exception {
        var tracker = tracker();
        var accepted = new CompletableFuture<Void>();
        when(tracker.handoffProviderAccepted(anyString(), any(), anyString())).thenReturn(accepted);
        var worker = worker(outcome, "true", tracker);
        var message = message();
        var submission = worker.submitPrepared(message, (payload, router) -> null, false);
        verify(tracker, never()).createAndEnqueueDlrAsync(anyInt(), any(), any(), any(), any(), any(), anyInt(), any(), any());
        assertThat(submission.completion().toCompletableFuture()).isNotDone();
        accepted.complete(null);
        int state = switch (outcome) {
            case "delivered" -> StandardMessage.DLR_STAT_DELIVRD;
            case "failed" -> StandardMessage.DLR_STAT_FAILED;
            default -> StandardMessage.DLR_STAT_EXPIRED;
        };
        String error = Integer.toString(switch (outcome) {
            case "delivered" -> StandardMessage.DLR_ERR_SUCCESS;
            case "failed" -> StandardMessage.DLR_ERR_SMS_FAILED;
            default -> StandardMessage.DLR_ERR_SMS_EXPIRED;
        });
        verify(tracker).createAndEnqueueDlrAsync(eq(message.msgId), eq(message.extrid), eq((String) message.field1),
                eq(message.from), eq(message.to), eq(""), eq(state), eq(error), isNull());
        assertThat(submission.completion().toCompletableFuture()).isCompleted();
        assertThat(message.extrid).startsWith("sim-");
    }

    @Test
    void retriesOnlyFailedReceiptHandoffWithoutRepeatingAcceptanceOrMetrics() throws Exception {
        var tracker = tracker();
        when(tracker.createAndEnqueueDlrAsync(anyInt(), any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("store unavailable")),
                        CompletableFuture.completedFuture(null));
        var worker = worker("delivered", "true", tracker);
        var registry = new SimpleMeterRegistry();
        try {
            worker.providerMetrics = new ProviderRequestMetrics(registry);
            var submission = worker.submitPrepared(message(), (payload, router) -> null, false);
            assertThat(submission.completion().toCompletableFuture()).isNotDone();
            submission.retryPendingHandoffs();
            assertThat(submission.completion().toCompletableFuture()).isCompleted();
            submission.retryPendingHandoffs();
            verify(tracker, times(1)).handoffProviderAccepted(anyString(), any(), anyString());
            verify(tracker, times(2)).createAndEnqueueDlrAsync(anyInt(), any(), any(), any(), any(), any(), anyInt(), any(), any());
            assertThat(registry.get("sendium.sms.provider.attempts").tag("provider", "simulator.demo")
                    .tag("kind", "initial").counter().count()).isEqualTo(1);
        } finally {
            registry.close();
        }
    }

    @Test
    void failedAcceptanceNeverGeneratesReceiptUntilRetried() throws Exception {
        var tracker = tracker();
        when(tracker.handoffProviderAccepted(anyString(), any(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("unavailable")),
                        CompletableFuture.completedFuture(null));
        var submission = worker("delivered", "true", tracker).submitPrepared(message(), (payload, router) -> null, false);
        assertThat(submission.completion().toCompletableFuture()).isNotDone();
        verify(tracker, never()).createAndEnqueueDlrAsync(anyInt(), any(), any(), any(), any(), any(), anyInt(), any(), any());
        submission.retryPendingHandoffs();
        assertThat(submission.completion().toCompletableFuture()).isCompleted();
    }

    @Test
    void receiptOptOutDoesNotCreateOrphanCorrelations() throws Exception {
        var tracker = tracker();
        var message = message();
        worker("delivered", "false", tracker).submitPrepared(message, (payload, router) -> null, false);
        var worker = worker("delivered", "true", tracker);
        message.dlrReturnMetadata = null;
        worker.submitPrepared(message, (payload, router) -> null, false);
        verify(tracker, never()).handoffProviderAccepted(any(), any(), any());
        verify(tracker, never()).createAndEnqueueDlrAsync(anyInt(), any(), any(), any(), any(), any(), anyInt(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"invalid,true", "delivered,invalid"})
    void invalidSettingsAreRejected(String outcome, String generate) {
        assertThatThrownBy(() -> worker(outcome, generate, tracker()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("simulatorConfiguration|error");
    }

    @Test
    void stoppedOrPausedProviderDoesNotConsumeMessage() throws Exception {
        var tracker = tracker();
        var worker = worker("delivered", "true", tracker);
        worker.setKeepOnRunning(false);
        assertThatThrownBy(() -> worker.submitPrepared(message(), (payload, router) -> null, false))
                .hasMessageContaining("stoppedOrPaused");
        verifyNoInteractions(tracker);
    }

    @Test
    void standardTrackerPersistsHttpAndQueuesSmppReceipts(@TempDir Path root) throws Exception {
        try (var storage = FileDlrStorage.open(root)) {
            var service = mock(DlrService.class);
            when(service.recordProviderAcceptedAsync(any(), anyString(), anyString())).thenAnswer(call -> {
                storage.recordProviderAccepted(call.getArgument(0), call.getArgument(1), call.getArgument(2));
                return CompletableFuture.completedFuture(java.util.Optional.empty());
            });
            when(service.resolveDlrAsync(anyString(), anyString(), anyInt(), anyString())).thenAnswer(call ->
                    CompletableFuture.completedFuture(storage.resolveDlr(call.getArgument(0), call.getArgument(1),
                            MessageState.MessageStatus.DELIVERED, call.getArgument(2), call.getArgument(3))));
            var resources = mock(WorkerResourceProvider.class);
            when(resources.isDlrPersistenceEnabled()).thenReturn(true);
            when(resources.getDlrService()).thenReturn(service);
            var worker = worker("delivered", "true", tracker());
            worker.workerResourcesForTest(resources);
            worker.attach(new StandardMessageTracker(worker));
            var http = message();
            worker.submitPrepared(http, (payload, router) -> null, false).completion().toCompletableFuture().join();
            assertThat(storage.claimDueHttpDeliveries(1)).singleElement()
                    .extracting(MessageState::getGatewayMsgId).isEqualTo(http.serial);
            var smpp = message();
            smpp.dlrReturnMetadata = DlrReturnMetadata.smpp("account", "system", smpp.from, smpp.to);
            worker.submitPrepared(smpp, (payload, router) -> null, false).completion().toCompletableFuture().join();
            assertThat(storage.listPendingSmppDeliveries("system")).hasSize(1);
            assertThat(worker.getRouterQueue().dequeue(10).serial).isEqualTo(smpp.serial);
        }
    }

    @SuppressWarnings("unchecked")
    private static Tracker<StandardMessage> tracker() {
        var tracker = (Tracker<StandardMessage>) mock(Tracker.class);
        when(tracker.handoffProviderAccepted(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(tracker.createAndEnqueueDlrAsync(anyInt(), any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(tracker.getHashedMessageID(anyString())).thenReturn("hash");
        return tracker;
    }

    private static TestSimulator worker(String outcome, String generate, Tracker<StandardMessage> tracker) {
        var configuration = mock(SendiumConfigurationProvider.class);
        when(configuration.getPrpt(any(String[].class))).thenAnswer(call -> {
            String[] parameter = call.getArgument(0);
            if (parameter[0].endsWith(".outcome")) {
                return outcome;
            }
            if (parameter[0].endsWith(".generateDlrs")) {
                return generate;
            }
            return parameter[1];
        });
        var worker = new TestSimulator();
        worker.setupInstance(configuration, "demo", new Queue<>());
        worker.attach(tracker);
        return worker;
    }

    private static StandardMessage message() {
        var message = new StandardMessage();
        message.serial = UUID.randomUUID().toString();
        message.from = "sender";
        message.to = "recipient";
        message.body = "test";
        message.dlrReturnMetadata = DlrReturnMetadata.http("account", message.from, message.to, "https://callback.test");
        return message;
    }

    private static final class TestSimulator extends SimulatorWorker {
        private Consumer<StandardMessage> preparation = ignored -> { };

        @Override
        public void checkBeforeDoMessageFilters(StandardMessage message) {
            preparation.accept(message);
        }

        void attach(Tracker<StandardMessage> tracker) {
            messageTracker = tracker;
        }
        void workerResourcesForTest(WorkerResourceProvider resources) {
            workerResources = resources;
        }
    }
}
