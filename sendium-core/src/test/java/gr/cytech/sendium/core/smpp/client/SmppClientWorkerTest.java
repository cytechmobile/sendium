package gr.cytech.sendium.core.smpp.client;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.PduAsyncResponse;
import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.tlv.Tlv;
import com.cloudhopper.smpp.type.Address;
import gr.cytech.sendium.conf.PropertyChangeListener;
import gr.cytech.sendium.conf.SendiumConfigurationProvider;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.queue.Queue;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.worker.DlrStorageException;
import gr.cytech.sendium.core.worker.ForwardMoService;
import gr.cytech.sendium.core.worker.Tracker;
import gr.cytech.sendium.external.WorkerResourceProvider;
import gr.cytech.sendium.routing.StandardOutboundDispatch;
import gr.cytech.sendium.routing.StandardRoutingManager;
import gr.cytech.sendium.routing.RoutingLookupResult;
import org.junit.jupiter.api.Test;
import utils.OutboundIngressFixture;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class SmppClientWorkerTest {

    @Test
    void workerSlotAndRateGatePreventConcurrentPreparationAndEarlySending() throws Exception {
        var scheduler = new ScheduledThreadPoolExecutor(2);
        var rateGate = new CompletableFuture<Void>();
        try (var fixture = new OutboundIngressFixture(2);
             var dispatch = new StandardOutboundDispatch(fixture.coordinator, mock(StandardRoutingManager.class), scheduler)) {
            var worker = new TestSmppClientWorker(new TestConfigurationProvider(), new Queue<>(), new CapturingTracker());
            worker.rateGate = rateGate;
            var executions = new java.util.ArrayList<StandardOutboundDispatch.ProviderExecution>();
            for (int index = 0; index < 2; index++) {
                var message = messageWithNetwork();
                message.body = "hello";
                fixture.coordinator.admit(new SourceId(UUID.randomUUID()), message);
            }
            fixture.coordinator.selectAndStage(2);
            for (int index = 0; index < 2; index++) {
                var selected = fixture.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
                fixture.coordinator.route(selected, new Destination<>(worker.getFullName(), selected.message()));
                executions.add(dispatch.submitToProvider(
                        fixture.coordinator.takeForDestination(worker.getFullName(), Duration.ZERO).orElseThrow(), worker));
            }
            assertThat(worker.rateEntered.await(5, TimeUnit.SECONDS)).isTrue();
            scheduler.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertThat(worker.rateChecks).isEqualTo(1);
            assertThat(worker.preparations).isZero();
            assertThat(worker.coordinatedRequests).isEmpty();
            rateGate.complete(null);
            worker.awaitRequests(2);
            worker.coordinatedRequests.forEach(request -> respond(handler(worker), request, SmppConstants.STATUS_OK));
            for (var execution : executions) {
                execution.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            assertThat(worker.rateChecks).isEqualTo(2);
            assertThat(worker.preparations).isEqualTo(2);
        } finally {
            rateGate.complete(null);
            scheduler.shutdownNow();
        }
    }

    @Test
    void failedRetryLookupCanResumeWithoutLosingParentOrRepeatingOriginalSubmission() throws Exception {
        var scheduler = new ScheduledThreadPoolExecutor(1);
        try (var fixture = new OutboundIngressFixture(1)) {
            var worker = new TestSmppClientWorker(new TestConfigurationProvider(Map.of(
                    "status.retry.router", Integer.toString(SmppConstants.STATUS_INVDSTADR))), new Queue<>(), new CapturingTracker());
            var routing = mock(StandardRoutingManager.class);
            var lookupTried = new CountDownLatch(1);
            when(routing.lookupForLifecycle(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
                lookupTried.countDown();
                throw new IllegalStateException("routing temporarily unavailable");
            });
            try (var dispatch = new StandardOutboundDispatch(fixture.coordinator, routing, scheduler)) {
                var message = messageWithNetwork();
                message.body = "hello";
                SourceId source = new SourceId(UUID.randomUUID());
                fixture.coordinator.admit(source, message);
                fixture.coordinator.selectAndStage(1);
                var selected = fixture.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
                fixture.coordinator.route(selected, new Destination<>(worker.getFullName(), selected.message()));
                var work = fixture.coordinator.takeForDestination(worker.getFullName(), Duration.ZERO).orElseThrow();
                var execution = dispatch.submitToProvider(work, worker);
                worker.awaitRequests(1);
                dispatch.quiesce();
                assertThat(dispatch.awaitProviderDrain(Duration.ZERO)).isFalse();
                assertThatThrownBy(() -> dispatch.submitToProvider(work, worker)).isInstanceOf(IllegalStateException.class);
                respond(handler(worker), worker.coordinatedRequests.getFirst(), SmppConstants.STATUS_INVDSTADR);
                assertThat(lookupTried.await(5, TimeUnit.SECONDS)).isTrue();
                scheduler.submit(() -> { }).get(5, TimeUnit.SECONDS);
                assertThat(execution.attempts().getLast().state()).isEqualTo(StandardOutboundDispatch.AttemptState.FAILED);
                assertThat(execution.attempts().getLast().failure()).isInstanceOf(IllegalStateException.class);
                assertThat(execution.completion().toCompletableFuture()).isNotDone();
                assertThat(fixture.pending.find(source)).isPresent();
                org.mockito.Mockito.doReturn(new RoutingLookupResult(java.util.List.of(worker), true))
                        .when(routing).lookupForLifecycle(org.mockito.ArgumentMatchers.any());
                execution.retryPendingHandoffs();
                worker.awaitRequests(2);
                respond(handler(worker), worker.coordinatedRequests.getLast(), SmppConstants.STATUS_OK);
                execution.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertThat(dispatch.awaitProviderDrain(Duration.ofSeconds(1))).isTrue();
                assertThat(worker.coordinatedRequests).hasSize(2);
                assertThat(fixture.pending.find(source)).isEmpty();
            }
            assertThat(scheduler.isShutdown()).isFalse();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void preparedSubmissionMapsCharactersBeforeProviderRequestGeneration() throws Exception {
        var worker = new TestSmppClientWorker(new TestConfigurationProvider(), new Queue<>(), new CapturingTracker());
        worker.mapCharacters = true;
        var message = messageWithNetwork();
        message.body = "aaa";
        var submission = worker.submitPreparedCoordinated(message, (payload, policy) -> {
            throw new AssertionError("No retry expected");
        });
        assertThat(CharsetUtil.decode(worker.coordinatedRequests.getFirst().getShortMessage(), CharsetUtil.NAME_GSM))
                .isEqualTo("bbb");
        respond(handler(worker), worker.coordinatedRequests.getFirst(), SmppConstants.STATUS_OK);
        submission.completion().toCompletableFuture().join();
    }

    @Test
    void beforeProcessingDropCompletesWithoutSendingToProvider() throws Exception {
        var worker = new TestSmppClientWorker(new TestConfigurationProvider(), new Queue<>(), new CapturingTracker());
        worker.preparationStatus = gr.cytech.sendium.external.filter.FilterStatusCodes.DROP;
        var submission = worker.submitPreparedCoordinated(messageWithNetwork(), (payload, policy) -> {
            throw new AssertionError("Dropped message must not retry");
        });
        submission.completion().toCompletableFuture().join();
        assertThat(worker.coordinatedRequests).isEmpty();
        assertThat(worker.coordinatedHandoffs).isZero();
    }

    @Test
    void preparationReenqueueWithoutRouterRetainsOwnershipInSameWorkerReplacement() throws Exception {
        var worker = new TestSmppClientWorker(new TestConfigurationProvider(), null, new CapturingTracker());
        worker.preparationStatus = gr.cytech.sendium.external.filter.FilterStatusCodes.REENQUEUE;
        var replacement = new CompletableFuture<Void>();
        var retried = new java.util.ArrayList<StandardMessage>();
        var original = messageWithNetwork();
        var submission = worker.submitPreparedCoordinated(original, (payload, policy) -> {
            assertThat(policy).isEqualTo(SmppClientWorker.NackHandlePolicy.RETRY_WORKER);
            retried.add(payload);
            return replacement;
        });
        assertThat(retried).containsExactly(original);
        assertThat(worker.coordinatedRequests).isEmpty();
        assertThat(submission.completion().toCompletableFuture()).isNotDone();
        replacement.complete(null);
        submission.completion().toCompletableFuture().join();
    }

    @Test
    void routerRetryTracksAnotherProviderWhileOriginalMultipartRequestIsOutstanding() throws Exception {
        try (var fixture = new OutboundIngressFixture(1)) {
            var first = new TestSmppClientWorker(new TestConfigurationProvider(Map.of(
                    "status.retry.router", Integer.toString(SmppConstants.STATUS_INVDSTADR))), new Queue<>(), new CapturingTracker());
            var second = spy(new TestSmppClientWorker(new TestConfigurationProvider(), new Queue<>(), new CapturingTracker()));
            when(second.getFullName()).thenReturn("smppclient.second");
            var routing = mock(StandardRoutingManager.class);
            when(routing.lookupForLifecycle(org.mockito.ArgumentMatchers.any())).thenReturn(
                    new RoutingLookupResult(java.util.List.of(second), true));
            try (var retryDispatch = new StandardOutboundDispatch(fixture.coordinator, routing)) {
                SourceId source = new SourceId(UUID.randomUUID());
                var message = messageWithNetwork();
                message.body = "a".repeat(200);
                fixture.coordinator.admit(source, message);
                fixture.coordinator.selectAndStage(1);
                var selected = fixture.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
                fixture.coordinator.route(selected, new Destination<>(first.getFullName(), selected.message()));
                var parent = fixture.coordinator.takeForDestination(first.getFullName(), Duration.ZERO).orElseThrow();
                var execution = retryDispatch.submitToProvider(parent, first);
                first.awaitRequests(2);
                respond(handler(first), first.coordinatedRequests.getFirst(), SmppConstants.STATUS_INVDSTADR);
                second.awaitRequests(2);
                assertThat(parent.destination()).isEqualTo(first.getFullName());
                assertThat(execution.attempts()).extracting(StandardOutboundDispatch.AttemptSnapshot::destination)
                        .containsExactly(first.getFullName(), second.getFullName());
                second.coordinatedRequests.forEach(request -> respond(handler(second), request, SmppConstants.STATUS_OK));
                assertThat(execution.completion().toCompletableFuture()).isNotDone();
                assertThat(fixture.pending.find(source)).isPresent();
                respond(handler(first), first.coordinatedRequests.getLast(), SmppConstants.STATUS_OK);
                execution.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertThat(fixture.pending.find(source)).isEmpty();
                assertThat(first.preparations).isEqualTo(1);
                assertThat(second.preparations).isEqualTo(1);
                assertThat(first.rateChecks).isEqualTo(1);
                assertThat(second.rateChecks).isEqualTo(1);
                assertThat(first.getRouterQueue().isEmpty()).isTrue();
                assertThat(second.getRouterQueue().isEmpty()).isTrue();
            }
        }
    }

    @Test
    void pausedAttemptWaitsWithoutSendingAndPreparesAfterResume() throws Exception {
        try (var fixture = new OutboundIngressFixture(1);
             var dispatch = new StandardOutboundDispatch(fixture.coordinator, mock(StandardRoutingManager.class))) {
            var worker = new TestSmppClientWorker(new TestConfigurationProvider(), new Queue<>(), new CapturingTracker());
            worker.testPaused = true;
            SourceId source = new SourceId(UUID.randomUUID());
            fixture.coordinator.admit(source, messageWithNetwork());
            fixture.coordinator.selectAndStage(1);
            var selected = fixture.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            selected.message().body = "hello";
            fixture.coordinator.route(selected, new Destination<>(worker.getFullName(), selected.message()));
            var work = fixture.coordinator.takeForDestination(worker.getFullName(), Duration.ZERO).orElseThrow();
            var execution = dispatch.submitToProvider(work, worker);
            assertThat(worker.pauseChecked.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.coordinatedRequests).isEmpty();
            assertThat(worker.preparations).isZero();
            assertThat(fixture.pending.find(source)).isPresent();
            worker.testPaused = false;
            worker.awaitRequests(1);
            respond(handler(worker), worker.coordinatedRequests.getFirst(), SmppConstants.STATUS_OK);
            execution.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(worker.preparations).isEqualTo(1);
            assertThat(worker.rateChecks).isEqualTo(1);
        }
    }

    @Test
    void firstSendFailureAbortsRemainingSubmitsAndRetriesOriginalPayload() throws Exception {
        var worker = new TestSmppClientWorker(new TestConfigurationProvider(), new Queue<>(), new CapturingTracker());
        worker.failSendNumber = 1;
        var message = messageWithNetwork();
        message.body = "a".repeat(200);
        var replacement = new CompletableFuture<Void>();
        var retries = new java.util.ArrayList<StandardMessage>();
        var submission = worker.submitCoordinated(message, (payload, policy) -> {
            retries.add(payload);
            return replacement;
        });
        assertThat(worker.coordinatedRequests).hasSize(1);
        assertThat(retries).containsExactly(message);
        assertThat(submission.completion().toCompletableFuture()).isNotDone();
        replacement.complete(null);
        submission.completion().toCompletableFuture().join();
    }

    @Test
    void coordinatedAcceptanceWaitsForAsynchronousTrackerHandoff() throws Exception {
        var accepted = new CompletableFuture<Void>();
        var tracker = new CapturingTracker() {
            @Override
            public CompletionStage<Void> handoffProviderAccepted(String hash, StandardMessage message, String providerId) {
                return accepted;
            }
        };
        var worker = new TestSmppClientWorker(new TestConfigurationProvider(), new Queue<>(), tracker);
        worker.realCoordinatedHandoff = true;
        var message = messageWithNetwork();
        message.body = "hello";
        var submission = worker.submitCoordinated(message, (payload, policy) -> {
            throw new AssertionError("No retry expected");
        });
        respond(handler(worker), worker.coordinatedRequests.getFirst(), SmppConstants.STATUS_OK);
        assertThat(submission.completion().toCompletableFuture()).isNotDone();
        accepted.complete(null);
        submission.completion().toCompletableFuture().join();
    }

    @Test
    void coordinatedExpiryGoesThroughFailurePolicyWithoutLegacyQueueInsertion() throws Exception {
        var routerQueue = new Queue<StandardMessage>();
        var worker = new TestSmppClientWorker(new TestConfigurationProvider(), routerQueue, new CapturingTracker());
        var message = messageWithNetwork();
        message.body = "hello";
        var retried = new java.util.ArrayList<StandardMessage>();
        var replacement = new CompletableFuture<Void>();
        var submission = worker.submitCoordinated(message, (payload, policy) -> {
            retried.add(payload);
            return replacement;
        });
        handler(worker).firePduRequestExpired(worker.coordinatedRequests.getFirst());
        assertThat(retried).containsExactly(message);
        assertThat(routerQueue.isEmpty()).isTrue();
        assertThat(submission.completion().toCompletableFuture()).isNotDone();
        replacement.complete(null);
        submission.completion().toCompletableFuture().join();
    }

    @Test
    void firstPartRouterRetryKeepsParentSourcesThroughOriginalAndReplacementParts() throws Exception {
        try (var fixture = new OutboundIngressFixture(1)) {
            var worker = new TestSmppClientWorker(new TestConfigurationProvider(Map.of(
                    "status.retry.router", Integer.toString(SmppConstants.STATUS_INVDSTADR))), new Queue<>(), new CapturingTracker());
            var message = messageWithNetwork();
            message.body = "a".repeat(200);
            SourceId source = new SourceId(UUID.randomUUID());
            fixture.coordinator.admit(source, message);
            fixture.coordinator.selectAndStage(1);
            var selected = fixture.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            fixture.coordinator.route(selected, new Destination<>(worker.getFullName(), selected.message()));
            var work = fixture.coordinator.takeForDestination(worker.getFullName(), Duration.ZERO).orElseThrow();
            var routing = mock(StandardRoutingManager.class);
            when(routing.lookupForLifecycle(org.mockito.ArgumentMatchers.any())).thenReturn(
                    new RoutingLookupResult(java.util.List.of(worker), true));
            var execution = new StandardOutboundDispatch(fixture.coordinator, routing).submitToProvider(work, worker);
            worker.awaitRequests(2);
            var session = handler(worker);
            worker.expectedRequests = 4;
            respond(session, worker.coordinatedRequests.getFirst(), SmppConstants.STATUS_INVDSTADR);
            assertThat(worker.requestsReady.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.coordinatedRequests).hasSize(4);
            respond(session, worker.coordinatedRequests.get(2), SmppConstants.STATUS_OK);
            respond(session, worker.coordinatedRequests.get(3), SmppConstants.STATUS_OK);
            assertThat(fixture.pending.find(source)).isPresent();
            assertThat(execution.completion().toCompletableFuture()).isNotDone();
            respond(session, worker.coordinatedRequests.get(1), SmppConstants.STATUS_OK);
            execution.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(fixture.pending.find(source)).isEmpty();
            assertThat(worker.getRouterQueue().isEmpty()).isTrue();
        }
    }

    @Test
    void coordinatedSessionCallbacksCompleteRealLifecycleOnlyAfterEveryPart() throws Exception {
        try (var fixture = new OutboundIngressFixture(1)) {
            var worker = new TestSmppClientWorker(new TestConfigurationProvider(), new Queue<>(), new CapturingTracker());
            var message = messageWithNetwork();
            message.body = "a".repeat(200);
            SourceId source = new SourceId(UUID.randomUUID());
            fixture.coordinator.admit(source, message);
            fixture.coordinator.selectAndStage(1);
            var selected = fixture.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            fixture.coordinator.route(selected, new Destination<>(worker.getFullName(), selected.message()));
            var work = fixture.coordinator.takeForDestination(worker.getFullName(), Duration.ZERO).orElseThrow();
            var dispatch = new StandardOutboundDispatch(fixture.coordinator, mock(StandardRoutingManager.class));
            var execution = dispatch.submitToProvider(work, worker);
            worker.awaitRequests(2);
            var session = handler(worker);
            respond(session, worker.coordinatedRequests.getLast(), SmppConstants.STATUS_OK);
            assertThat(fixture.pending.find(source)).isPresent();
            assertThat(execution.completion().toCompletableFuture()).isNotDone();
            respond(session, worker.coordinatedRequests.getFirst(), SmppConstants.STATUS_OK);
            execution.completion().toCompletableFuture().join();
            assertThat(fixture.pending.find(source)).isEmpty();
            respond(session, worker.coordinatedRequests.getFirst(), SmppConstants.STATUS_OK);
            assertThat(worker.coordinatedHandoffs).isEqualTo(2);
        }
    }

    private static void respond(SmppClientSessionHandler handler, SubmitSm request, int status) {
        var response = request.createResponse();
        response.setCommandStatus(status);
        response.setMessageId("provider-id");
        var async = mock(PduAsyncResponse.class);
        when(async.getRequest()).thenReturn(request);
        when(async.getResponse()).thenReturn(response);
        handler.fireExpectedPduResponseReceived(async);
    }

    @Test
    void coordinatedMultipartWaitsForAllResponsesAndIgnoresDuplicateCallbacks() throws Exception {
        var worker = new TestSmppClientWorker(new TestConfigurationProvider(), new Queue<>(), new CapturingTracker());
        var message = messageWithNetwork();
        message.body = "a".repeat(200);
        var submission = worker.submitCoordinated(message, (payload, policy) -> {
            throw new AssertionError("No retry expected");
        });
        assertThat(worker.coordinatedRequests).hasSize(2);
        Object first = worker.coordinatedRequests.getFirst().getReferenceObject();
        Object last = worker.coordinatedRequests.getLast().getReferenceObject();
        worker.handleCoordinatedResponse(last, SmppConstants.STATUS_OK, "second");
        assertThat(submission.completion().toCompletableFuture()).isNotDone();
        worker.handleCoordinatedResponse(last, SmppConstants.STATUS_OK, "duplicate");
        worker.handleCoordinatedResponse(first, SmppConstants.STATUS_OK, "first");
        submission.completion().toCompletableFuture().join();
        assertThat(worker.coordinatedHandoffs).isEqualTo(2);
        assertThat(worker.success).isEmpty();
        assertThat(worker.failures).isEmpty();
    }

    @Test
    void coordinatedRouterRetryPreservesFirstWholeMessageAndLaterPartPayloads() throws Exception {
        var worker = new TestSmppClientWorker(new TestConfigurationProvider(Map.of(
                "status.retry.router", Integer.toString(SmppConstants.STATUS_INVDSTADR))), new Queue<>(), new CapturingTracker());
        var message = messageWithNetwork();
        message.body = "a".repeat(200);
        var retries = new java.util.ArrayList<StandardMessage>();
        var replacement = new CompletableFuture<Void>();
        var submission = worker.submitCoordinated(message, (payload, policy) -> {
            assertThat(policy).isEqualTo(SmppClientWorker.NackHandlePolicy.RETRY_ROUTER);
            retries.add(payload);
            return replacement;
        });
        worker.coordinatedRequests.forEach(request -> worker.handleCoordinatedResponse(
                request.getReferenceObject(), SmppConstants.STATUS_INVDSTADR, null));
        assertThat(retries).hasSize(2);
        assertThat(retries.getFirst()).isSameAs(message);
        assertThat(retries.getFirst().body).hasSize(200);
        assertThat(retries.getLast()).isNotSameAs(message);
        assertThat(retries.getLast().binheader).isNotBlank();
        assertThat(retries.getLast().body.length()).isLessThan(200);
        assertThat(submission.completion().toCompletableFuture()).isNotDone();
        assertThat(worker.failures).isEmpty();
        replacement.complete(null);
        submission.completion().toCompletableFuture().join();
    }

    @Test
    void coordinatedTrackingFailureRetriesOnlyHandoffWithoutResubmittingProvider() throws Exception {
        var worker = new TestSmppClientWorker(new TestConfigurationProvider(), new Queue<>(), new CapturingTracker());
        worker.failCoordinatedHandoff = true;
        var message = messageWithNetwork();
        message.body = "hello";
        var submission = worker.submitCoordinated(message, (payload, policy) -> {
            throw new AssertionError("No provider retry expected");
        });
        worker.handleCoordinatedResponse(worker.coordinatedRequests.getFirst().getReferenceObject(),
                SmppConstants.STATUS_OK, "accepted");
        assertThat(submission.handoffFailures()).hasSize(1);
        assertThat(submission.completion().toCompletableFuture()).isNotDone();
        worker.failCoordinatedHandoff = false;
        submission.retryPendingHandoffs();
        submission.completion().toCompletableFuture().join();
        assertThat(worker.coordinatedRequests).hasSize(1);
        assertThat(worker.coordinatedHandoffs).isEqualTo(2);
    }

    @Test
    void defaultsSensitiveDiagnosticLoggingOff() {
        TestConfigurationProvider config = new TestConfigurationProvider();
        TestSmppClientWorker worker = new TestSmppClientWorker(config, new Queue<>(), new CapturingTracker());

        assertThat(worker.isPrintMsgs()).isFalse();
        assertThat(config.getBlnPrpt(worker._logPdus)).isFalse();
        assertThat(config.getBlnPrpt(worker._logBytes)).isFalse();
        assertThat(config.getBlnPrpt(worker._printResps)).isFalse();
        assertThat(config.getBlnPrpt(worker._printMos)).isFalse();
    }

    @Test
    void dlrProviderNameDefaultsToWorkerFullName() {
        TestSmppClientWorker worker = new TestSmppClientWorker(
                new TestConfigurationProvider(), new Queue<>(), new CapturingTracker());

        assertThat(worker.getDlrProviderName()).isEqualTo("test");
    }

    @Test
    void dlrProviderNameUsesConfiguredSharedNamespace() {
        TestSmppClientWorker worker = new TestSmppClientWorker(
                new TestConfigurationProvider(Map.of("msg.hash.prefix", "provider-cluster")),
                new Queue<>(), new CapturingTracker());

        assertThat(worker.getDlrProviderName()).isEqualTo("provider-cluster");
    }

    @Test
    void blankDlrProviderNameFallsBackToWorkerFullName() {
        TestSmppClientWorker worker = new TestSmppClientWorker(
                new TestConfigurationProvider(Map.of("msg.hash.prefix", "   ")),
                new Queue<>(), new CapturingTracker());

        assertThat(worker.getDlrProviderName()).isEqualTo("test");
    }

    @Test
    void parseDlrAndCreateResponse_whenReceiptIsValid_enqueuesDlrWithRegisteredTlvs() throws Exception {
        TestConfigurationProvider config = new TestConfigurationProvider(Map.of(
                "registered.tlvs.dlr", "carrier_1400"));
        CapturingTracker tracker = new CapturingTracker();
        TestSmppClientWorker worker = new TestSmppClientWorker(config, new Queue<>(), tracker);

        DeliverSm deliverSm = new DeliverSm();
        deliverSm.setSourceAddress(new Address((byte) 1, (byte) 1, "smsc"));
        deliverSm.setDestAddress(new Address((byte) 1, (byte) 1, "recipient"));
        deliverSm.setDataCoding(SmppConstants.DATA_CODING_DEFAULT);
        deliverSm.setShortMessage(CharsetUtil.encode(
                "id:abc123 sub:001 dlvrd:001 submit date:2401010000 done date:2401010001 stat:DELIVRD err:000 text:ok",
                CharsetUtil.NAME_GSM));
        deliverSm.addOptionalParameter(new Tlv((short) 1400, "network-a".getBytes()));

        PduResponse response = worker.parseDlrAndCreateResponse(deliverSm);

        assertThat(response.getCommandStatus()).isEqualTo(SmppConstants.STATUS_OK);
        assertThat(tracker.dlrProviderMessageId).isEqualTo("abc123");
        assertThat(tracker.dlrFrom).isEqualTo("smsc");
        assertThat(tracker.dlrTo).isEqualTo("recipient");
        assertThat(tracker.dlrState).isEqualTo(StandardMessage.DLR_STAT_DELIVRD);
        assertThat(tracker.dlrTlvs).containsEntry("carrier_1400", "network-a");
    }

    @Test
    void parseDlrAndCreateResponse_whenReceiptIsIntermediate_acknowledgesWithoutEnqueuing() throws Exception {
        CapturingTracker tracker = new CapturingTracker();
        TestSmppClientWorker worker = new TestSmppClientWorker(
                new TestConfigurationProvider(), new Queue<>(), tracker);

        for (String state : Set.of("ACCEPTD", "ENROUTE")) {
            DeliverSm deliverSm = new DeliverSm();
            deliverSm.setSourceAddress(new Address((byte) 1, (byte) 1, "smsc"));
            deliverSm.setDestAddress(new Address((byte) 1, (byte) 1, "recipient"));
            deliverSm.setDataCoding(SmppConstants.DATA_CODING_DEFAULT);
            deliverSm.setShortMessage(CharsetUtil.encode(
                    "id:abc123 sub:001 dlvrd:000 submit date:2401010000 done date: stat:" + state +
                            " err:000 text:pending",
                    CharsetUtil.NAME_GSM));

            PduResponse response = worker.parseDlrAndCreateResponse(deliverSm);

            assertThat(response.getCommandStatus()).isEqualTo(SmppConstants.STATUS_OK);
        }
        assertThat(tracker.dlrAttempts).isZero();
    }

    @Test
    void parseDlrAndCreateResponse_whenStorageFails_returnsSystemErrorForProviderRetry() throws Exception {
        CapturingTracker tracker = new CapturingTracker();
        tracker.failDlrCreation = true;
        TestSmppClientWorker worker = new TestSmppClientWorker(
                new TestConfigurationProvider(), new Queue<>(), tracker);
        DeliverSm deliverSm = new DeliverSm();
        deliverSm.setSourceAddress(new Address((byte) 1, (byte) 1, "smsc"));
        deliverSm.setDestAddress(new Address((byte) 1, (byte) 1, "recipient"));
        deliverSm.setDataCoding(SmppConstants.DATA_CODING_DEFAULT);
        deliverSm.setShortMessage(CharsetUtil.encode(
                "id:abc123 sub:001 dlvrd:001 submit date:2401010000 done date:2401010001 stat:DELIVRD err:000 text:ok",
                CharsetUtil.NAME_GSM));

        PduResponse response = worker.parseDlrAndCreateResponse(deliverSm);

        assertThat(response.getCommandStatus()).isEqualTo(SmppConstants.STATUS_SYSERR);
        assertThat(tracker.dlrAttempts).isEqualTo(1);
    }

    @Test
    void parseDlrAndCreateResponse_whenReceiptHasNoMessageId_returnsSystemError() throws Exception {
        TestSmppClientWorker worker = new TestSmppClientWorker(new TestConfigurationProvider(), new Queue<>(), new CapturingTracker());
        DeliverSm deliverSm = new DeliverSm();
        deliverSm.setSourceAddress(new Address((byte) 1, (byte) 1, "smsc"));
        deliverSm.setDestAddress(new Address((byte) 1, (byte) 1, "recipient"));
        deliverSm.setDataCoding(SmppConstants.DATA_CODING_DEFAULT);
        deliverSm.setShortMessage(CharsetUtil.encode(
                "id: sub:001 dlvrd:000 submit date:2401010000 done date:2401010001 stat:UNDELIV err:001 text:failed",
                CharsetUtil.NAME_GSM));

        PduResponse response = worker.parseDlrAndCreateResponse(deliverSm);

        assertThat(response.getCommandStatus()).isEqualTo(SmppConstants.STATUS_SYSERR);
    }

    @Test
    void parseMoAndCreateResponse_whenForwardUrlConfigured_forwardsDecodedMo() throws Exception {
        TestConfigurationProvider config = new TestConfigurationProvider(Map.of(
                "forward.mo.url", "http://example.test/mo",
                "forward.mo.format", "JSON"));
        CapturingForwardMoService forwardMoService = new CapturingForwardMoService();
        TestSmppClientWorker worker = new TestSmppClientWorker(config, new Queue<>(), new CapturingTracker(),
                new TestWorkerResourceProvider(forwardMoService));
        DeliverSm deliverSm = new DeliverSm();
        deliverSm.setSourceAddress(new Address((byte) 1, (byte) 1, "sender"));
        deliverSm.setDestAddress(new Address((byte) 1, (byte) 1, "shortcode"));
        deliverSm.setDataCoding(SmppConstants.DATA_CODING_DEFAULT);
        deliverSm.setShortMessage(CharsetUtil.encode("hello\0", CharsetUtil.NAME_GSM));

        PduResponse response = worker.parseMoAndCreateResponse(deliverSm);

        assertThat(response.getCommandStatus()).isEqualTo(SmppConstants.STATUS_OK);
        assertThat(forwardMoService.forwardUrl).isEqualTo("http://example.test/mo");
        assertThat(forwardMoService.format).isEqualTo(ForwardMoService.ForwardFormat.JSON);
        assertThat(forwardMoService.context.from()).isEqualTo("sender");
        assertThat(forwardMoService.context.to()).isEqualTo("shortcode");
        assertThat(forwardMoService.context.text()).isEqualTo("hello");
        assertThat(forwardMoService.context.ingateway()).isEmpty();
        assertThat(forwardMoService.context.messageCenter()).isEmpty();
        assertThat(forwardMoService.context.dataCoding()).isEqualTo(SmppConstants.DATA_CODING_DEFAULT);
    }

    @Test
    void parseMoAndCreateResponse_whenPayloadTlvAndUdhi_forwardsBodyWithoutHeader() throws Exception {
        TestConfigurationProvider config = new TestConfigurationProvider(Map.of(
                "forward.mo.url", "http://example.test/mo"));
        CapturingForwardMoService forwardMoService = new CapturingForwardMoService();
        TestSmppClientWorker worker = new TestSmppClientWorker(config, new Queue<>(), new CapturingTracker(),
                new TestWorkerResourceProvider(forwardMoService));
        SubmitSm submitSm = new SubmitSm();
        submitSm.setSourceAddress(new Address((byte) 1, (byte) 1, "sender"));
        submitSm.setDestAddress(new Address((byte) 1, (byte) 1, "shortcode"));
        submitSm.setDataCoding(SmppConstants.DATA_CODING_DEFAULT);
        submitSm.setEsmClass(SmppConstants.ESM_CLASS_UDHI_MASK);
        submitSm.addOptionalParameter(new Tlv(
                SmppConstants.TAG_MESSAGE_PAYLOAD,
                new byte[]{0x05, 0x00, 0x03, 0x01, 0x02, 0x01, 'H', 'i'}));

        PduResponse response = worker.parseMoAndCreateResponse(submitSm);

        assertThat(response.getCommandStatus()).isEqualTo(SmppConstants.STATUS_OK);
        assertThat(forwardMoService.context.text()).isEqualTo("Hi");
        assertThat(forwardMoService.context.from()).isEqualTo("sender");
        assertThat(forwardMoService.context.to()).isEqualTo("shortcode");
    }

    @Test
    void handleResponse_whenStatusOk_marksSuccess() {
        TestSmppClientWorker worker = new TestSmppClientWorker(new TestConfigurationProvider(), new Queue<>(), new CapturingTracker());
        StandardMessage msg = messageWithNetwork();

        worker.handleResponse(handler(worker), SmppConstants.STATUS_OK, "smsc-1", msg);

        assertThat(worker.success).containsExactly(msg);
        assertThat(worker.temporaryFailures).isEmpty();
        assertThat(worker.failures).isEmpty();
    }

    @Test
    void handleResponse_whenRetryWorkerStatus_marksTemporaryFailure() {
        TestSmppClientWorker worker = new TestSmppClientWorker(new TestConfigurationProvider(), new Queue<>(), new CapturingTracker());
        StandardMessage msg = messageWithNetwork();

        worker.handleResponse(handler(worker), SmppConstants.STATUS_THROTTLED, null, msg);

        assertThat(worker.temporaryFailures).containsExactly(msg);
    }

    @Test
    void handleResponse_whenRetryRouterStatus_removesHlrAndFailsToRouter() {
        TestConfigurationProvider config = new TestConfigurationProvider(Map.of(
                "status.retry.router", Integer.toString(SmppConstants.STATUS_INVDSTADR)));
        TestSmppClientWorker worker = new TestSmppClientWorker(config, new Queue<>(), new CapturingTracker());
        StandardMessage msg = messageWithNetwork();

        worker.handleResponse(handler(worker), SmppConstants.STATUS_INVDSTADR, null, msg);

        assertThat(worker.failures).containsExactly(msg);
        assertThat(msg.cnetwork).isZero();
        assertThat(msg.outgateway).isEmpty();
    }

    @Test
    void handleResponse_whenFailStatus_recordsFailureDlr() {
        TestConfigurationProvider config = new TestConfigurationProvider(Map.of(
                "status.fail", Integer.toString(SmppConstants.STATUS_INVMSGLEN),
                "resp.errcodes", SmppConstants.STATUS_INVMSGLEN + "_7"));
        CapturingTracker tracker = new CapturingTracker();
        TestSmppClientWorker worker = new TestSmppClientWorker(config, new Queue<>(), tracker);
        StandardMessage msg = messageWithNetwork();

        worker.handleResponse(handler(worker), SmppConstants.STATUS_INVMSGLEN, "smsc-2", msg);

        assertThat(tracker.dlrMqId).isEqualTo(17);
        assertThat(tracker.dlrProviderMessageId).isEqualTo("smsc-2");
        assertThat(tracker.dlrState).isEqualTo(StandardMessage.DLR_STAT_FAILED);
        assertThat(tracker.dlrErrorCode).isEqualTo("7");
    }

    @Test
    void updateSendStatusAndProviderMessageId_whenStorageFails_keepsSubmitResponseCallbackAlive() {
        CapturingTracker tracker = new CapturingTracker();
        tracker.failProviderLink = true;
        TestSmppClientWorker worker = new TestSmppClientWorker(
                new TestConfigurationProvider(), new Queue<>(), tracker);
        StandardMessage msg = messageWithNetwork();
        msg.serial = "gateway-17";

        String providerMessageId = worker.updateSendStatusAndProviderMessageId("smsc-17", msg);

        assertThat(providerMessageId).isEqualTo("smsc-17");
        assertThat(tracker.linkAttempts).isEqualTo(1);
    }

    @Test
    void updateSendStatusAndProviderMessageId_whenResponseIdIsBlank_skipsTracking() {
        CapturingTracker tracker = new CapturingTracker();
        TestSmppClientWorker worker = new TestSmppClientWorker(
                new TestConfigurationProvider(), new Queue<>(), tracker);
        StandardMessage msg = messageWithNetwork();
        msg.serial = "gateway-17";

        String providerMessageId = worker.updateSendStatusAndProviderMessageId("   ", msg);

        assertThat(providerMessageId).isNull();
        assertThat(tracker.linkAttempts).isZero();
    }

    @Test
    void failMessage_whenStorageFails_attemptsDlrWithoutEscapingCallback() {
        CapturingTracker tracker = new CapturingTracker();
        tracker.failDlrCreation = true;
        TestSmppClientWorker worker = new TestSmppClientWorker(
                new TestConfigurationProvider(), new Queue<>(), tracker);
        StandardMessage msg = messageWithNetwork();
        msg.serial = "gateway-17";

        worker.failMessage(SmppConstants.STATUS_INVMSGLEN, "smsc-17", msg);

        assertThat(tracker.linkAttempts).isZero();
        assertThat(tracker.dlrAttempts).isEqualTo(1);
    }

    private static SmppClientSessionHandler handler(TestSmppClientWorker worker) {
        return new SmppClientSessionHandler(worker, new SmppClientWorker.ConnectionInfo(
                null, "localhost", 2775, SmppClientWorker.ConnectionType.NORMAL));
    }

    private static StandardMessage messageWithNetwork() {
        StandardMessage msg = new StandardMessage();
        msg.msgId = 17;
        msg.from = "from";
        msg.to = "to";
        msg.cnetwork = 20201;
        msg.outgateway = "hlr-route";
        return msg;
    }

    private static class TestSmppClientWorker extends SmppClientWorker<StandardMessage> {
        private volatile boolean testPaused;
        private volatile int preparations;
        private volatile int rateChecks;
        private CompletableFuture<Void> rateGate;
        private final CountDownLatch rateEntered = new CountDownLatch(1);
        private boolean mapCharacters;
        private gr.cytech.sendium.external.filter.FilterStatusCodes preparationStatus;
        private final CountDownLatch pauseChecked = new CountDownLatch(1);

        @Override
        public boolean isPause() {
            pauseChecked.countDown();
            return testPaused || super.isPause();
        }

        @Override
        protected void applyRateLimit() {
            rateChecks++;
            if (rateGate != null) {
                rateEntered.countDown();
                rateGate.join();
            }
            super.applyRateLimit();
        }

        @Override
        protected void checkBeforeDoMessageFilters(StandardMessage message) throws java.io.IOException {
            preparations++;
            if (preparationStatus != null) {
                var status = preparationStatus;
                preparationStatus = null;
                throw new gr.cytech.sendium.external.filter.FilterException(null, status, message, "test filter");
            }
            super.checkBeforeDoMessageFilters(message);
        }
        private final java.util.concurrent.Semaphore requestsSeen = new java.util.concurrent.Semaphore(0);

        private void awaitRequests(int count) throws InterruptedException {
            while (coordinatedRequests.size() < count) {
                assertThat(requestsSeen.tryAcquire(5, TimeUnit.SECONDS)).isTrue();
            }
        }

        @Override
        protected String charMap(String body) {
            return mapCharacters ? body.replace('a', 'b') : body;
        }

        @Override
        public boolean verifyConnectivity() {
            return true;
        }
        private final java.util.List<SubmitSm> coordinatedRequests = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final CountDownLatch requestsReady = new CountDownLatch(1);
        private volatile int expectedRequests = Integer.MAX_VALUE;
        private int coordinatedHandoffs;
        private boolean failCoordinatedHandoff;
        private boolean realCoordinatedHandoff;
        private int failSendNumber;

        @Override
        protected void sendCoordinatedRequest(SubmitSm request) throws java.io.IOException {
            coordinatedRequests.add(request);
            requestsSeen.release();
            if (coordinatedRequests.size() == failSendNumber) {
                throw new java.io.IOException("submit unavailable");
            }
            if (coordinatedRequests.size() >= expectedRequests) {
                requestsReady.countDown();
            }
        }

        @Override
        protected CompletionStage<Void> coordinatedProviderHandoff(StandardMessage message, int status, String providerId) {
            if (realCoordinatedHandoff) {
                return super.coordinatedProviderHandoff(message, status, providerId);
            }
            coordinatedHandoffs++;
            return failCoordinatedHandoff ? CompletableFuture.failedStage(new DlrStorageException("unavailable")) :
                    CompletableFuture.completedStage(null);
        }
        private final java.util.List<StandardMessage> success = new java.util.ArrayList<>();
        private final java.util.List<StandardMessage> temporaryFailures = new java.util.ArrayList<>();
        private final java.util.List<StandardMessage> failures = new java.util.ArrayList<>();

        TestSmppClientWorker(SendiumConfigurationProvider configurationProvider,
                             Queue<StandardMessage> routerQueue,
                             Tracker<StandardMessage> tracker) {
            super(configurationProvider, routerQueue, new ScheduledThreadPoolExecutor(1));
            this.messageTracker = tracker;
            this.suspendAuto = false;
        }

        TestSmppClientWorker(SendiumConfigurationProvider configurationProvider,
                             Queue<StandardMessage> routerQueue,
                             Tracker<StandardMessage> tracker,
                             WorkerResourceProvider workerResourceProvider) {
            this(configurationProvider, routerQueue, tracker);
            this.workerResources = workerResourceProvider;
        }

        @Override
        protected void successMessage(String respMessageId, StandardMessage msg) {
            success.add(msg);
        }

        @Override
        public void onMessageTemporaryFailed(StandardMessage m) {
            temporaryFailures.add(m);
        }

        @Override
        public void onMessageFailed(StandardMessage m) {
            failures.add(m);
        }
    }

    private static class TestWorkerResourceProvider extends WorkerResourceProvider {
        private final ForwardMoService forwardMoService;

        private TestWorkerResourceProvider(ForwardMoService forwardMoService) {
            this.forwardMoService = forwardMoService;
        }

        @Override
        public ForwardMoService getForwardMoService() {
            return forwardMoService;
        }
    }

    private static class CapturingForwardMoService extends ForwardMoService {
        private String forwardUrl;
        private MoContext context;
        private ForwardFormat format;

        @Override
        public void forwardMo(String forwardUrl, MoContext ctx, ForwardFormat format) {
            this.forwardUrl = forwardUrl;
            this.context = ctx;
            this.format = format;
        }
    }

    private static class CapturingTracker implements Tracker<StandardMessage> {
        private int dlrMqId;
        private String dlrProviderMessageId;
        private String dlrFrom;
        private String dlrTo;
        private int dlrState;
        private String dlrErrorCode;
        private HashMap<String, String> dlrTlvs;
        private boolean failProviderLink;
        private boolean failDlrCreation;
        private int linkAttempts;
        private int dlrAttempts;

        @Override
        public void init() {
        }

        @Override
        public boolean stop() {
            return true;
        }

        @Override
        public void configure(String key, String newValue, String oldValue) {
        }

        @Override
        public int updateSendStatusAndExtID(String hashedProviderMessageId, StandardMessage message,
                                            String providerMessageId) {
            linkAttempts++;
            if (failProviderLink) {
                throw new DlrStorageException("Failed to link provider DLR ID");
            }
            return 1;
        }

        @Override
        public String getHashedMessageID(String messageId) {
            return "hashed-" + messageId;
        }

        @Override
        public String getVendorPriceGateway() {
            return "";
        }

        @Override
        public void createAndEnqueueDLR(int mqid, String providerMessageId, String hashedProviderMessageId,
                                        String from, String to, String body, int state, String errorCode,
                                        HashMap<String, String> tlvs) {
            dlrAttempts++;
            if (failDlrCreation) {
                throw new DlrStorageException("Failed to resolve DLR state");
            }
            this.dlrMqId = mqid;
            this.dlrProviderMessageId = providerMessageId;
            this.dlrFrom = from;
            this.dlrTo = to;
            this.dlrState = state;
            this.dlrErrorCode = errorCode;
            this.dlrTlvs = tlvs;
        }

        @Override
        public void createAndEnqueueSubmissionFailure(StandardMessage message, String providerMessageId,
                                                       String hashedProviderMessageId, String body,
                                                       int state, String errorCode,
                                                       HashMap<String, String> tlvs) {
            dlrAttempts++;
            if (failDlrCreation) {
                throw new DlrStorageException("Failed to persist submission failure DLR");
            }
            this.dlrMqId = message.msgId;
            this.dlrProviderMessageId = providerMessageId;
            this.dlrFrom = message.from;
            this.dlrTo = message.to;
            this.dlrState = state;
            this.dlrErrorCode = errorCode;
            this.dlrTlvs = tlvs;
        }

        @Override
        public int getConfiguredMccMnc() {
            return 0;
        }
    }

    private static class TestConfigurationProvider implements SendiumConfigurationProvider {
        private final Map<String, String> props = new HashMap<>();

        TestConfigurationProvider() {
            this(Map.of());
        }

        TestConfigurationProvider(Map<String, String> overrides) {
            props.putAll(overrides);
        }

        @Override
        public long getLongPrpt(String[] props) {
            return Long.parseLong(getPrpt(props));
        }

        @Override
        public long getLongPrpt(String prop, long def) {
            return Long.parseLong(this.props.getOrDefault(prop, Long.toString(def)));
        }

        @Override
        public String getPrpt(String[] props) {
            return this.props.getOrDefault(props[0], props[1]);
        }

        @Override
        public String getPrpt(String prop) {
            return props.get(prop);
        }

        @Override
        public String getPrpt(String property, String defaultValue) {
            return props.getOrDefault(property, defaultValue);
        }

        @Override
        public int getIntPrpt(String[] props) {
            return Integer.parseInt(getPrpt(props));
        }

        @Override
        public int getIntPrpt(String s, int intPrpt) {
            return Integer.parseInt(props.getOrDefault(s, Integer.toString(intPrpt)));
        }

        @Override
        public boolean getBlnPrpt(String[] props) {
            return Boolean.parseBoolean(getPrpt(props));
        }

        @Override
        public boolean getBlnPrpt(String s, boolean defaultValue) {
            return Boolean.parseBoolean(props.getOrDefault(s, Boolean.toString(defaultValue)));
        }

        @Override
        public void loadDefaultParams(String[][] prms) {
            for (String[] prm : prms) {
                props.putIfAbsent(prm[0], prm[1]);
            }
        }

        @Override
        public void loadDefaultParams(String prefix, String[][] prms) {
            for (String[] prm : prms) {
                props.putIfAbsent(prefix + "." + prm[0], prm[1]);
            }
        }

        @Override
        public boolean storeProperties(Map<String, String> props) {
            this.props.putAll(props);
            return true;
        }

        @Override
        public void addPropertyChangeListener(PropertyChangeListener propertyChanged) {
        }

        @Override
        public void removePropertyChangeListener(PropertyChangeListener propertyChangeListener) {
        }

        @Override
        public Set<String> getAllKeysReadOnly() {
            return Set.copyOf(props.keySet());
        }

        @Override
        public String setProperty(String s, String aFalse) {
            return props.put(s, aFalse);
        }
    }
}
