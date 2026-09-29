package gr.cytech.sendium.core.smpp.server;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.pdu.Pdu;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import gr.cytech.sendium.core.message.DlrReturnMetadata;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.queue.Queue;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.util.MessageUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import utils.OutboundIngressFixture;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@Timeout(10)
class SmppLifecycleAdmissionTest {
    @Test
    void completeSubmissionIsOwnedBeforeStatusOkAndDoesNotEnterLegacyQueues() throws Exception {
        try (var lifecycle = new OutboundIngressFixture(1); var worker = new TestWorker()) {
            worker.setIngressCoordinator(lifecycle.coordinator);
            var event = submission(null, "hello", 42);
            worker.onResponse = response -> {
                var source = ArgumentCaptor.forClass(SourceId.class);
                verify(lifecycle.coordinator).admit(source.capture(), eq(event.pMsg));
                assertThat(source.getValue().value()).isEqualTo(UUID.fromString(response.getMessageId()));
                assertThat(lifecycle.pending.find(source.getValue()).orElseThrow().serial).isEqualTo(response.getMessageId());
            };
            worker.enqueueIn(event);
            assertThat(worker.responses).singleElement().satisfies(response -> {
                assertThat(response.getCommandStatus()).isEqualTo(SmppConstants.STATUS_OK);
                assertThat(response.getSequenceNumber()).isEqualTo(42);
                assertThat(response.getMessageId()).isEqualTo(event.pMsg.serial);
            });
            assertThat(event.waitingForResponse).isFalse();
            assertThat(worker.getInEventQueue()).isEmpty();
            assertThat(worker.legacyQueue.isEmpty()).isTrue();
            assertThat(lifecycle.coordinator.selectAndStage(1)).isEqualTo(1);
            var selected = lifecycle.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(selected.message().dlrReturnMetadata).isEqualTo(event.pMsg.dlrReturnMetadata);
            assertThat(selected.message().ctstamp).isEqualTo(event.localTimestamp.getTime());
            lifecycle.coordinator.discard(selected.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
        }
    }

    @Test
    void multipartPartsAreAcknowledgedIndividuallyThenPublishedWithAllSources() throws Exception {
        try (var lifecycle = new OutboundIngressFixture(2); var worker = new TestWorker()) {
            worker.setIngressCoordinator(lifecycle.coordinator);
            worker.onResponse = response -> {
                var accepted = worker.getInEventQueue().peek();
                assertThat(accepted.sourceIds).hasSize(1);
                assertThat(accepted.sourceIds.iterator().next().value()).isEqualTo(
                        UUID.fromString(response.getMessageId()));
                accepted.sourceIds.forEach(source -> assertThat(lifecycle.pending.find(source)).isPresent());
                assertThat(lifecycle.coordinator.selectAndStage(2)).isZero();
            };
            var first = submission("0500037F0201", "Hello ", 1);
            worker.enqueueIn(first);
            var firstAccepted = worker.getInEventQueue().remove();
            worker.handleIngressMessages(List.of(firstAccepted));
            assertThat(worker.responses).hasSize(1);
            assertThat(lifecycle.coordinator.selectAndStage(2)).isZero();
            var second = submission("0500037F0202", "world", 2);
            worker.enqueueIn(second);
            var secondAccepted = worker.getInEventQueue().remove();
            worker.handleIngressMessages(List.of(secondAccepted));
            var ready = worker.getInEventQueue().remove();
            Set<SourceId> sources = new HashSet<>(firstAccepted.sourceIds);
            sources.addAll(secondAccepted.sourceIds);
            assertThat(ready.sourceIds).isEqualTo(sources);
            assertThat(sources).extracting(SourceId::value).containsExactlyInAnyOrder(
                    UUID.fromString(first.pMsg.serial), UUID.fromString(second.pMsg.serial));
            assertThat(ready.submitSm).isNull();
            assertThat(ready.waitingForResponse).isFalse();
            assertThat(ready.pMsg.body).isEqualTo("Hello world");
            assertThat(ready.pMsg.reassembledParts).containsExactly(first.pMsg.serial, second.pMsg.serial);
            assertThat(lifecycle.pending.find(firstAccepted.sourceIds.iterator().next()).orElseThrow().body).isEqualTo("Hello ");
            worker.handleIngressMessages(List.of(ready));
            assertThat(lifecycle.coordinator.selectAndStage(2)).isEqualTo(1);
            var selected = lifecycle.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(selected.sources()).isEqualTo(sources);
            assertThat(selected.message().body).isEqualTo("Hello world");
            lifecycle.coordinator.route(selected, new Destination<>("provider", selected.message()));
            var work = lifecycle.coordinator.takeForDestination("provider", Duration.ZERO).orElseThrow();
            lifecycle.coordinator.complete(work.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
            sources.forEach(source -> assertThat(lifecycle.pending.find(source)).isEmpty());
            assertThat(worker.responses).hasSize(2).allSatisfy(response ->
                    assertThat(response.getCommandStatus()).isEqualTo(SmppConstants.STATUS_OK));
            assertThat(worker.legacyQueue.isEmpty()).isTrue();
        }
    }

    @Test
    void publicationFailureRetriesPreparedWorkWithoutAnotherAcknowledgementOrReassembly() throws Exception {
        try (var lifecycle = new OutboundIngressFixture(2); var worker = new TestWorker()) {
            worker.setIngressCoordinator(lifecycle.coordinator);
            worker.enqueueIn(submission("0500037F0201", "one", 1));
            worker.enqueueIn(submission("0500037F0202", "two", 2));
            worker.processQueued();
            var ready = worker.getInEventQueue().remove();
            doThrow(new OutboundStorageException(OutboundStage.Role.PENDING,
                    OutboundStorageException.Reason.UNAVAILABLE, "publication unavailable"))
                    .when(lifecycle.coordinator).publishReady(any(), any());
            worker.handleIngressMessages(List.of(ready));
            assertThat(worker.getInEventQueue()).containsExactly(ready);
            assertThat(lifecycle.coordinator.selectAndStage(2)).isZero();
            ready.sourceIds.forEach(source -> assertThat(lifecycle.pending.find(source)).isPresent());
            doCallRealMethod().when(lifecycle.coordinator).publishReady(any(), any());
            worker.processQueued();
            assertThat(lifecycle.coordinator.selectAndStage(2)).isEqualTo(1);
            assertThat(lifecycle.coordinator.takeForRouting(Duration.ZERO).orElseThrow().message().body).isEqualTo("onetwo");
            assertThat(worker.responses).hasSize(2);
        }
    }

    @Test
    void expiryPublishesEachAcceptedPartIndependently() throws Exception {
        try (var lifecycle = new OutboundIngressFixture(2); var worker = new TestWorker()) {
            worker.setIngressCoordinator(lifecycle.coordinator);
            var first = submission("0500037F0301", "one", 1);
            var third = submission("0500037F0303", "three", 3);
            worker.enqueueIn(first);
            worker.enqueueIn(third);
            worker.processQueued();
            worker.expire(first.pMsg);
            var events = worker.queued();
            assertThat(events).hasSize(2).allSatisfy(event -> {
                assertThat(event.sourceIds).hasSize(1);
                assertThat(event.submitSm).isNull();
            });
            worker.handleIngressMessages(events);
            assertThat(lifecycle.coordinator.selectAndStage(2)).isEqualTo(2);
            var one = lifecycle.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            var three = lifecycle.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(one.message().body).isEqualTo("one");
            assertThat(three.message().body).isEqualTo("three");
            assertThat(one.sources()).doesNotContainAnyElementsOf(three.sources());
            assertThat(worker.responses).hasSize(2);
        }
    }

    @Test
    void ignoredDuplicateOrdinalStillReleasesItsAcceptedSourceWithTheOriginalGroup() throws Exception {
        try (var lifecycle = new OutboundIngressFixture(3); var worker = new TestWorker()) {
            worker.setIngressCoordinator(lifecycle.coordinator);
            worker.enqueueIn(submission("0500037F0201", "first", 1));
            worker.enqueueIn(submission("0500037F0201", "ignored", 2));
            worker.enqueueIn(submission("0500037F0202", "second", 3));
            worker.processQueued();
            var ready = worker.getInEventQueue().remove();
            assertThat(ready.sourceIds).hasSize(3);
            assertThat(ready.pMsg.body).isEqualTo("firstsecond");
            assertThat(ready.pMsg.reassembledParts).hasSize(2);
            worker.handleIngressMessages(List.of(ready));
            lifecycle.coordinator.selectAndStage(1);
            var selected = lifecycle.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(selected.sources()).hasSize(3);
            lifecycle.coordinator.discard(selected.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
            ready.sourceIds.forEach(source -> assertThat(lifecycle.pending.find(source)).isEmpty());
            assertThat(worker.responses).hasSize(3);
        }
    }

    @Test
    void replayingProcessedRawEventsDoesNotReassembleOrAcknowledgeThemAgain() throws Exception {
        try (var lifecycle = new OutboundIngressFixture(2); var worker = new TestWorker()) {
            worker.setIngressCoordinator(lifecycle.coordinator);
            worker.enqueueIn(submission("0500037F0201", "one", 1));
            worker.enqueueIn(submission("0500037F0202", "two", 2));
            var raw = worker.queued();
            worker.handleIngressMessages(raw);
            worker.handleIngressMessages(raw);
            assertThat(worker.getInEventQueue()).hasSize(1);
            worker.processQueued();
            assertThat(lifecycle.coordinator.selectAndStage(2)).isEqualTo(1);
            var selected = lifecycle.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(selected.message().body).isEqualTo("onetwo");
            assertThat(selected.sources()).hasSize(2);
            assertThat(worker.responses).hasSize(2);
        }
    }

    @Test
    void expiryDoesNotClaimAnAcceptedPartStillWaitingInTheIngressQueue() throws Exception {
        try (var lifecycle = new OutboundIngressFixture(2); var worker = new TestWorker()) {
            worker.setIngressCoordinator(lifecycle.coordinator);
            var first = submission("0500037F0201", "first", 1);
            var second = submission("0500037F0202", "second", 2);
            worker.enqueueIn(first);
            worker.processQueued();
            worker.enqueueIn(second);
            worker.expire(first.pMsg);
            var events = worker.queued();
            var unprocessed = events.stream().filter(event -> event.submitSm != null).findFirst().orElseThrow();
            var expired = events.stream().filter(event -> event.submitSm == null).findFirst().orElseThrow();
            assertThat(expired.sourceIds).doesNotContainAnyElementsOf(unprocessed.sourceIds);
            worker.handleIngressMessages(List.of(expired));
            assertThat(lifecycle.coordinator.selectAndStage(2)).isEqualTo(1);
            assertThat(lifecycle.coordinator.takeForRouting(Duration.ZERO).orElseThrow().message().body).isEqualTo("first");
            worker.handleIngressMessages(List.of(unprocessed));
            assertThat(lifecycle.coordinator.selectAndStage(2)).isZero();
            worker.expire(second.pMsg);
            worker.processQueued();
            assertThat(lifecycle.coordinator.selectAndStage(2)).isEqualTo(1);
            assertThat(lifecycle.coordinator.takeForRouting(Duration.ZERO).orElseThrow().message().body).isEqualTo("second");
            assertThat(worker.responses).hasSize(2);
        }
    }

    @Test
    void capacityAndQuiescenceRejectBeforeSuccessAndNeverFallBackToLegacyQueue() {
        try (var lifecycle = new OutboundIngressFixture(1); var worker = new TestWorker()) {
            worker.setIngressCoordinator(lifecycle.coordinator);
            worker.enqueueIn(submission("0500037F0201", "held", 1));
            worker.enqueueIn(submission("0500037F0202", "full", 2));
            lifecycle.coordinator.quiesce();
            worker.enqueueIn(submission(null, "unavailable", 3));
            assertThat(worker.responses).extracting(SubmitSmResp::getCommandStatus)
                    .containsExactly(SmppConstants.STATUS_OK, SmppConstants.STATUS_THROTTLED, SmppConstants.STATUS_SYSERR);
            assertThat(worker.getInEventQueue()).hasSize(1);
            assertThat(worker.legacyQueue.isEmpty()).isTrue();
        }
    }

    @Test
    void unsupportedHeaderIsRejectedUsingTheExistingReassemblyValidator() {
        try (var lifecycle = new OutboundIngressFixture(1); var worker = new TestWorker()) {
            worker.setIngressCoordinator(lifecycle.coordinator);
            worker.enqueueIn(submission("not-a-supported-concatenation-header", "bad", 1));
            assertThat(worker.responses).singleElement().satisfies(response ->
                    assertThat(response.getCommandStatus()).isEqualTo(SmppConstants.STATUS_SUBMITFAIL));
            assertThat(worker.getInEventQueue()).isEmpty();
            assertThat(lifecycle.coordinator.selectAndStage(1)).isZero();
        }
    }

    @Test
    void ingressBindingCannotChangeAfterAdmission() {
        try (var lifecycle = new OutboundIngressFixture(1); var worker = new TestWorker()) {
            worker.setIngressCoordinator(lifecycle.coordinator);
            worker.enqueueIn(submission(null, "one", 1));
            assertThatThrownBy(() -> worker.setIngressCoordinator(lifecycle.coordinator))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private static InEvent<StandardMessage> submission(String udh, String body, int sequence) {
        var message = new StandardMessage();
        message.from = "sender";
        message.to = "306900000001";
        message.owner_id = "account-a";
        message.systemId = "system-a";
        message.body = body;
        message.binheader = udh;
        message.acked = true;
        message.dlrReturnMetadata = DlrReturnMetadata.smpp("account-a", "system-a", message.from, message.to);
        var request = new SubmitSm();
        request.setSequenceNumber(sequence);
        return new InEvent<>(message, request, 1, new Timestamp(1_000));
    }

    private static final class TestWorker extends SmppServerWorker<StandardMessage> implements AutoCloseable {
        private final Queue<StandardMessage> legacyQueue;
        private final List<SubmitSmResp> responses = new ArrayList<>();
        private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        private Consumer<SubmitSmResp> onResponse = ignored -> {};

        private TestWorker() {
            this(new Queue<>());
        }

        private TestWorker(Queue<StandardMessage> queue) {
            super(new SmppServerWorkerReassemblyTest.TestConfigurationProvider(), "ingress", queue);
            legacyQueue = queue;
            messagePartsHandler.stop();
            setMessagePartsHandler(new MessagePartsHandler<>(new CcatMessagePartsEventsListener(), 60_000, scheduler));
        }

        @Override
        public void enqueueOut(Pdu event) {
            var response = (SubmitSmResp) event;
            onResponse.accept(response);
            responses.add(response);
        }

        private List<InEvent<StandardMessage>> queued() {
            var events = new ArrayList<InEvent<StandardMessage>>();
            getInEventQueue().drainTo(events);
            return events;
        }

        private void processQueued() {
            handleIngressMessages(queued());
        }

        private void expire(StandardMessage part) {
            messagePartsHandler.new DelayedMessagePartsTask(MessageUtil.getMessageReference(part)).call();
        }

        @Override
        public void close() {
            messagePartsHandler.stop();
            scheduler.shutdownNow();
        }
    }
}
