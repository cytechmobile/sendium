package gr.cytech.sendium.core.smpp.server;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.pdu.Pdu;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.DefaultOutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.queue.Queue;
import gr.cytech.sendium.core.storage.file.FilePendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemoryRoutedWorkStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SharedPendingRecoveryTest {
    @TempDir
    Path root;

    @Test
    void smppSuccessObservesCommittedOrdinaryAndMultipartSourcesAndCapacityFailureDoesNotAccept() {
        var pending = new FilePendingMessageStore(root, 2);
        var router = new MemorySelectedRouterStore<>(pending, 2);
        var routed = new MemoryRoutedWorkStore<StandardMessage>(2, message -> PendingMessageData.capture(message).restore());
        var accepted = new ArrayList<SourceId>();
        var rejected = new ArrayList<Integer>();
        var worker = new SmppServerWorker<StandardMessage>(
                new SmppServerWorkerReassemblyTest.TestConfigurationProvider(), "smpp", new Queue<>()) {
            @Override
            public void enqueueOut(Pdu event) {
                var response = (SubmitSmResp) event;
                if (response.getCommandStatus() == SmppConstants.STATUS_OK) {
                    var source = new SourceId(UUID.fromString(response.getMessageId()));
                    // Check at the acknowledgement boundary, not after enqueueIn returns.
                    assertThat(pending.find(source)).isPresent();
                    assertThat(root.resolve("sources").resolve(response.getMessageId().substring(0, 2))
                            .resolve(response.getMessageId() + ".cbor")).isRegularFile();
                    accepted.add(source);
                } else {
                    rejected.add(response.getCommandStatus());
                }
            }
        };
        try (var coordinator = new DefaultOutboundCoordinator<>(pending, router, routed)) {
            coordinator.start();
            worker.setIngressCoordinator(coordinator);
            worker.enqueueIn(new InEvent<>(part("ordinary", null), new SubmitSm(), 1, new Timestamp(System.currentTimeMillis())));
            worker.enqueueIn(new InEvent<>(part("A", "0500037F0201"), new SubmitSm(), 1, new Timestamp(System.currentTimeMillis())));
            worker.enqueueIn(new InEvent<>(part("overflow", null), new SubmitSm(), 1, new Timestamp(System.currentTimeMillis())));
            assertThat(accepted).hasSize(2);
            assertThat(rejected).containsExactly(SmppConstants.STATUS_THROTTLED);
            assertThat(pending.sourcesAfter(null, 3)).hasSize(2);
        } finally {
            worker.messagePartsHandler.stop();
        }
        try (var reopened = new FilePendingMessageStore(root, 2)) {
            reopened.open();
            assertThat(reopened.find(accepted.get(0)).orElseThrow().body).isEqualTo("ordinary");
            assertThat(reopened.find(accepted.get(1)).orElseThrow().body).isEqualTo("A");
        }
    }

    @Test
    void unsafeSharedRecoveryRejectsStartupAndSubmissionWithoutRecordingAcceptance() {
        var pending = new FilePendingMessageStore(root, 16);
        var router = new MemorySelectedRouterStore<>(pending, 4);
        var routed = new MemoryRoutedWorkStore<StandardMessage>(16, message -> PendingMessageData.capture(message).restore());
        var responses = new ArrayList<Pdu>();
        var worker = new SmppServerWorker<StandardMessage>(
                new SmppServerWorkerReassemblyTest.TestConfigurationProvider(), "smpp", new Queue<>()) {
            @Override
            public void enqueueOut(Pdu event) {
                responses.add(event);
            }
        };
        try (var coordinator = new DefaultOutboundCoordinator<>(pending, router, routed)) {
            coordinator.start();
            worker.setIngressCoordinator(coordinator);
            coordinator.beginShutdown();
            assertThatThrownBy(worker::start).hasMessageContaining("pendingRecoveryNotReady");
            worker.enqueueIn(new InEvent<>(part("A", "0500037F0201"), new SubmitSm(), 1,
                    new Timestamp(System.currentTimeMillis())));
            assertThat(responses).singleElement().satisfies(response ->
                    assertThat(response.getCommandStatus()).isEqualTo(SmppConstants.STATUS_SYSERR));
            assertThat(pending.sourcesAfter(null, 16)).isEmpty();
            assertThat(worker.getInEventQueue()).isEmpty();
        } finally {
            worker.messagePartsHandler.stop();
        }
    }

    @Test
    void workersShareAuthoritativeRecoveryAndStoppingOneDoesNotStopTheOther() throws Exception {
        var pending = new FilePendingMessageStore(root, 16);
        var router = new MemorySelectedRouterStore<>(pending, 4);
        var routed = new MemoryRoutedWorkStore<StandardMessage>(16, message -> PendingMessageData.capture(message).restore());
        var first = worker();
        var second = worker();
        try (var coordinator = new DefaultOutboundCoordinator<>(pending, router, routed)) {
            coordinator.start();
            first.setIngressCoordinator(coordinator);
            second.setIngressCoordinator(coordinator);
            var recovery = coordinator.pendingMultipartRecovery().orElseThrow();
            assertThat(first.messagePartsHandler.hasSharedRecovery()).isTrue();
            assertThat(second.messagePartsHandler.hasSharedRecovery()).isTrue();
            var a = part("A", "0500037F0201");
            var b = part("B", "0500037F0202");
            var sourceA = new SourceId(UUID.fromString(a.serial));
            var sourceB = new SourceId(UUID.fromString(b.serial));
            coordinator.acceptPart(sourceA, a, Duration.ofSeconds(60));
            a.body = "not authoritative";
            first.messagePartsHandler.addAcceptedSource(sourceA);
            first.drainIngressAndMultipart();
            assertThat(recovery.isReady()).isTrue();
            assertThat(first.messagePartsHandler.isRecoveryReady()).isFalse();
            assertThatThrownBy(() -> first.messagePartsHandler.addAcceptedSource(sourceA)).hasMessageContaining("handlerStopped");
            second.configReassemblingTimeout();
            assertThat(second.messagePartsHandler.hasSharedRecovery()).isTrue();
            coordinator.acceptPart(sourceB, b, Duration.ofSeconds(60));
            second.messagePartsHandler.addAcceptedSource(sourceB);
            assertThat(coordinator.selectToRouter(4)).isEqualTo(1);
            var selected = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(selected.message().body).isEqualTo("AB");
            assertThat(selected.sources()).containsExactlyInAnyOrder(sourceA, sourceB);
            assertThatThrownBy(() -> second.messagePartsHandler.reconstruct(pending, 2, coordinator::makeHeldReady))
                    .hasMessageContaining("sharedRecoveryIsCoordinatorOwned");
            assertThatThrownBy(() -> second.setMessagePartsHandler(first.messagePartsHandler))
                    .hasMessageContaining("cannotReplaceSharedRecoveryHandler");
        } finally {
            first.messagePartsHandler.stop();
            second.messagePartsHandler.stop();
        }
    }

    private static SmppServerWorker<StandardMessage> worker() {
        return new SmppServerWorker<>(new SmppServerWorkerReassemblyTest.TestConfigurationProvider(), "smpp", new Queue<>());
    }

    private static StandardMessage part(String body, String header) {
        var message = new StandardMessage();
        message.serial = UUID.randomUUID().toString();
        message.owner_id = "account-a";
        message.systemId = "system-a";
        message.from = "sender";
        message.to = "306900000001";
        message.body = body;
        message.binheader = header;
        return message;
    }
}
