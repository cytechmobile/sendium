package gr.cytech.sendium.core.smpp.server;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.EnquireLink;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.type.SmppProcessingException;
import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.queue.Queue;
import gr.cytech.sendium.external.filter.FilterException;
import gr.cytech.sendium.external.filter.FilterStatusCodes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import utils.OutboundIngressFixture;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmppSubmissionMetricsTest {
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final MetricsWorker worker = new MetricsWorker();

    SmppSubmissionMetricsTest() {
        worker.bindSubmissionMetrics(metrics);
    }

    @AfterEach
    void close() {
        worker.messagePartsHandler.stop();
        metrics.close();
    }

    @Test
    void countsAcceptedAndCapacityRejectedSubmissionsWithoutCountingCleanup() throws Exception {
        try (var lifecycle = new OutboundIngressFixture(1)) {
            worker.setIngressCoordinator(lifecycle.coordinator);
            worker.enqueueIn(event(null));
            worker.enqueueIn(event(null));
            assertThat(count("accepted")).isEqualTo(1);
            assertThat(count("rejected")).isEqualTo(1);
            assertThat(lifecycle.coordinator.selectToRouter(1)).isEqualTo(1);
            var selected = lifecycle.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            lifecycle.coordinator.discard(selected.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
            assertThat(count("accepted")).isEqualTo(1);
            assertThat(count("rejected")).isEqualTo(1);
        }
    }

    @Test
    void duplicateMultipartAdmissionsCountButAssemblyAndRepeatedProcessingDoNot() throws Exception {
        try (var lifecycle = new OutboundIngressFixture(3)) {
            worker.setIngressCoordinator(lifecycle.coordinator);
            worker.reconstructPendingParts(lifecycle.pending, 1);
            worker.enqueueIn(event("0500037F0201"));
            worker.enqueueIn(event("0500037F0201"));
            worker.enqueueIn(event("0500037F0202"));
            assertThat(count("accepted")).isEqualTo(3);
            var events = List.copyOf(worker.getInEventQueue());
            worker.getInEventQueue().clear();
            worker.handleIngressMessages(events);
            worker.handleIngressMessages(events);
            assertThat(count("accepted")).isEqualTo(3);
            assertThat(count("rejected")).isZero();
            assertThat(lifecycle.pending.sourcesAfter(null, 4)).hasSize(3);
            assertThat(lifecycle.coordinator.selectToRouter(3)).isEqualTo(1);
        }
    }

    @Test
    void countsParsingProcessorAndStoppingRejections() throws Exception {
        var processor = mock(SubmitSmProcessor.class);
        var handler = handler(processor);
        handler.handleSubmitSm(new SubmitSm());
        var invalidSchedule = new SubmitSm();
        invalidSchedule.setScheduleDeliveryTime("invalid");
        handler.handleSubmitSm(invalidSchedule);
        var valid = new SubmitSm();
        valid.setShortMessage(new byte[]{65});
        when(processor.processSubmitSm(any(), any(), any(), any()))
                .thenThrow(new SmppProcessingException(SmppConstants.STATUS_SUBMITFAIL));
        handler.handleSubmitSm(valid);
        worker.stopAcceptingSubmitSm();
        handler.handleSubmitSm(new SubmitSm());
        assertThat(count("accepted")).isZero();
        assertThat(count("rejected")).isEqualTo(4);
    }

    @Test
    void countsBeforeInsertFilterDropWithoutAdmission() throws Exception {
        try (var lifecycle = new OutboundIngressFixture(1)) {
            worker.setIngressCoordinator(lifecycle.coordinator);
            var filter = mock(AbstractOutWorker.class);
            doThrow(new FilterException(filter, FilterStatusCodes.DROP, null, "blocked"))
                    .when(filter).doFilter(any(), any());
            worker.setBeforeInsertMessageFilters(List.of(filter));
            worker.enqueueIn(event(null));
            assertThat(count("accepted")).isZero();
            assertThat(count("rejected")).isEqualTo(1);
            assertThat(lifecycle.pending.sourcesAfter(null, 1)).isEmpty();
        }
    }

    @Test
    void bindingIsOptionalForProgrammaticallyConstructedWorkers() {
        var unbound = new MetricsWorker();
        try {
            unbound.enqueueOut(new SubmitSm().createResponse());
            assertThat(count("accepted")).isZero();
            assertThat(count("rejected")).isZero();
        } finally {
            unbound.messagePartsHandler.stop();
        }
    }

    @Test
    void workersShareTheSameBoundedSmppSeries() {
        var other = new MetricsWorker();
        try {
            other.bindSubmissionMetrics(metrics);
            worker.enqueueOut(new SubmitSm().createResponse());
            other.enqueueOut(new SubmitSm().createResponse());
            assertThat(count("accepted")).isEqualTo(2);
            assertThat(metrics.getMeters()).hasSize(2);
        } finally {
            other.messagePartsHandler.stop();
        }
    }

    @Test
    void excludesHousekeepingDeliveryTrafficAndUnqueuedResponses() {
        var handler = handler(mock(SubmitSmProcessor.class));
        handler.firePduRequestReceived(new EnquireLink());
        worker.enqueueOut(new DeliverSm());
        worker.enqueueOut(new DeliverSm().createResponse());
        assertThat(count("accepted")).isZero();
        assertThat(count("rejected")).isZero();
        doThrow(new RejectedExecutionException("stopped")).when(worker.outExecutor).execute(any());
        assertThatThrownBy(() -> worker.enqueueOut(new SubmitSm().createResponse()))
                .isInstanceOf(RejectedExecutionException.class);
        assertThat(count("accepted")).isZero();
    }

    private double count(String result) {
        return metrics.get("sendium.sms.submissions").tag("ingress", "smpp").tag("result", result).counter().count();
    }

    private SmppServerSessionHandler<StandardMessage> handler(SubmitSmProcessor<StandardMessage> processor) {
        var session = mock(SmppSession.class);
        var configuration = new SmppSessionConfiguration();
        configuration.setName("account");
        when(session.getConfiguration()).thenReturn(configuration);
        return new SmppServerSessionHandler<>(worker, 1L, session, mock(SmppSessionContext.class), processor);
    }

    private static InEvent<StandardMessage> event(String udh) {
        var message = new StandardMessage();
        message.owner_id = "account";
        message.systemId = "system";
        message.from = "sender";
        message.to = "recipient";
        message.body = "part";
        message.binheader = udh;
        return new InEvent<>(message, new SubmitSm(), 1, new Timestamp(System.currentTimeMillis()));
    }

    private static final class MetricsWorker extends SmppServerWorker<StandardMessage> {
        private MetricsWorker() {
            super(new SmppServerWorkerReassemblyTest.TestConfigurationProvider(), "metrics", new Queue<>());
            keepOnRunning = true;
            outExecutor = mock(ThreadPoolExecutor.class);
        }
    }
}
