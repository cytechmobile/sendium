package gr.cytech.sendium.core.smpp.server;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.Pdu;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import gr.cytech.sendium.conf.PropertyChangeListener;
import gr.cytech.sendium.conf.SendiumConfigurationProvider;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.queue.Queue;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmppServerWorkerReassemblyTest {

    @Test
    void completeUdhPartsAreReassembledAndRoutedToRouterQueue() throws Exception {
        Queue<StandardMessage> routerQueue = new Queue<>();
        TestSmppServerWorker worker = new TestSmppServerWorker(new TestConfigurationProvider(), routerQueue);
        SmppServerMessageStore<StandardMessage> store = mock(SmppServerMessageStore.class);
        when(store.persistsBeforeAcknowledgement()).thenReturn(true);
        worker.setMessageStore(store);
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        MessagePartsHandler<StandardMessage> handler = new MessagePartsHandler<>(
                worker.new CcatMessagePartsEventsListener(), TimeUnit.SECONDS.toMillis(30), executor);

        try {
            handler.addMessagePart(messagePart("0500037F0202", "World", "part-2"));
            handler.addMessagePart(messagePart("0500037F0201", "Hello ", "part-1"));

            assertThat(routerQueue.dequeue(10)).isNull();
            InEvent<StandardMessage> persisted = worker.getInEventQueue().poll(1_000, TimeUnit.MILLISECONDS);
            assertThat(persisted).isNotNull();
            worker.handlePersistedMessages(List.of(persisted));
            StandardMessage routed = routerQueue.dequeue(1_000);
            assertThat(routed.body).isEqualTo("Hello World");
            assertThat(routed.binheader).isNull();
            assertThat(routed.reassembledParts).containsExactly("part-1", "part-2");
            assertThat(worker.workerQueueMessages).isEmpty();

            assertThat(persisted.pMsg).isSameAs(routed);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void delayedUdhPartsAreRoutedToRouterQueueWithoutBecomingDeliverSm() throws Exception {
        Queue<StandardMessage> routerQueue = new Queue<>();
        TestSmppServerWorker worker = new TestSmppServerWorker(new TestConfigurationProvider(), routerQueue);
        SmppServerMessageStore<StandardMessage> store = mock(SmppServerMessageStore.class);
        when(store.persistsBeforeAcknowledgement()).thenReturn(true);
        worker.setMessageStore(store);
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        MessagePartsHandler<StandardMessage> handler = new MessagePartsHandler<>(
                worker.new CcatMessagePartsEventsListener(), 10, executor);
        StandardMessage part = messagePart("0500037F0201", "Hello ", "part-1");

        try {
            handler.addMessagePart(part);

            StandardMessage routed = routerQueue.dequeue(1_000);
            assertThat(routed).isSameAs(part);
            assertThat(routed.body).isEqualTo("Hello ");
            assertThat(routed.binheader).isEqualTo("0500037F0201");
            assertThat(routed.reassembledParts).isNull();
            assertThat(worker.workerQueueMessages).isEmpty();

            assertThat(worker.getInEventQueue()).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void reassembledDlrGeneratesDeliverSmPerOriginalPartIdWithSameStatus() throws Exception {
        TestSmppServerWorker worker = new TestSmppServerWorker(new TestConfigurationProvider(), new Queue<>());
        StandardMessage dlr = new StandardMessage();
        dlr.from = "306900000001";
        dlr.to = "sender";
        dlr.type = StandardMessage.MSG_DLR;
        dlr.state = StandardMessage.DLR_STAT_DELIVRD;
        dlr.errcode = "0";
        dlr.acked = true;
        dlr.reassembledParts = new ArrayList<>(List.of("part-1", "part-2", "part-3"));

        List<DeliverSm> deliverSms = worker.generateDeliverSmForDLR(dlr);

        assertThat(deliverSms).hasSize(3);
        assertThat(deliverSms.stream().map(DeliverSm::getReferenceObject).collect(Collectors.toList()))
                .containsExactly("part-1", "part-2", "part-3");
        assertThat(deliverSms).allSatisfy(deliverSm ->
                assertThat(deliverSm.getEsmClass()).isEqualTo(SmppConstants.ESM_CLASS_MT_SMSC_DELIVERY_RECEIPT));
        List<String> bodies = deliverSms.stream()
                .map(deliverSm -> CharsetUtil.decode(deliverSm.getShortMessage(), worker.getCharsetGsm()))
                .collect(Collectors.toList());
        assertThat(bodies).allSatisfy(body -> assertThat(body).contains("stat:DELIVRD"));
        assertThat(bodies).anySatisfy(body -> assertThat(body).contains("id:part-1"));
        assertThat(bodies).anySatisfy(body -> assertThat(body).contains("id:part-2"));
        assertThat(bodies).anySatisfy(body -> assertThat(body).contains("id:part-3"));
    }

    @Test
    void normalSubmissionRoutesAndAcknowledgesOnlyAfterPersistence() throws Exception {
        Queue<StandardMessage> routerQueue = new Queue<>();
        TestSmppServerWorker worker = new TestSmppServerWorker(new TestConfigurationProvider(), routerQueue);
        StandardMessage message = messagePart(null, "hello", null);
        SubmitSm submitSm = new SubmitSm();
        submitSm.setSequenceNumber(42);
        InEvent<StandardMessage> event = new InEvent<>(message, submitSm, 1,
                new Timestamp(System.currentTimeMillis()));

        worker.enqueueIn(event);

        InEvent<StandardMessage> queued = worker.getInEventQueue().poll();
        assertThat(queued).isSameAs(event);
        assertThat(routerQueue.dequeue(10)).isNull();
        assertThat(worker.outgoingPdus).isEmpty();

        worker.handlePersistedMessages(List.of(queued));

        assertThat(routerQueue.dequeue(1_000)).isSameAs(message);
        assertThat(worker.outgoingPdus).singleElement().satisfies(pdu -> {
            assertThat(pdu).isInstanceOf(SubmitSmResp.class);
            SubmitSmResp response = (SubmitSmResp) pdu;
            assertThat(response.getCommandStatus()).isEqualTo(SmppConstants.STATUS_OK);
            assertThat(response.getMessageId()).isEqualTo(message.serial);
        });
    }

    @Test
    void persistenceFailureReturnsSystemErrorWithoutRouting() throws Exception {
        Queue<StandardMessage> routerQueue = new Queue<>();
        TestSmppServerWorker worker = new TestSmppServerWorker(new TestConfigurationProvider(), routerQueue);
        StandardMessage message = messagePart(null, "hello", null);
        SubmitSm submitSm = new SubmitSm();
        InEvent<StandardMessage> event = new InEvent<>(message, submitSm, 1,
                new Timestamp(System.currentTimeMillis()));
        worker.enqueueIn(event);
        InEvent<StandardMessage> queued = worker.getInEventQueue().poll();

        worker.handleMessagePersistenceFailure(List.of(queued));

        assertThat(routerQueue.dequeue(10)).isNull();
        assertThat(worker.outgoingPdus).singleElement().satisfies(pdu -> {
            assertThat(pdu).isInstanceOf(SubmitSmResp.class);
            assertThat(pdu.getCommandStatus()).isEqualTo(SmppConstants.STATUS_SYSERR);
        });
    }

    @Test
    void submissionDuringShutdownIsRejectedWithoutQueueAdmission() throws Exception {
        Queue<StandardMessage> routerQueue = new Queue<>();
        TestSmppServerWorker worker = new TestSmppServerWorker(new TestConfigurationProvider(), routerQueue);
        worker.setKeepOnRunning(false);
        InEvent<StandardMessage> event = new InEvent<>(messagePart(null, "hello", null), new SubmitSm(), 1,
                new Timestamp(System.currentTimeMillis()));

        worker.enqueueIn(event);

        assertThat(worker.getInEventQueue()).isEmpty();
        assertThat(routerQueue.dequeue(10)).isNull();
        assertThat(worker.outgoingPdus).singleElement()
                .satisfies(pdu -> assertThat(pdu.getCommandStatus()).isEqualTo(SmppConstants.STATUS_SYSERR));
    }

    @Test
    void routerAdmissionFailureReturnsSystemErrorAfterPersistence() throws Exception {
        Queue<StandardMessage> routerQueue = new Queue<>() {
            @Override
            public void enqueue(StandardMessage message) throws InterruptedException {
                throw new InterruptedException("router unavailable");
            }
        };
        TestSmppServerWorker worker = new TestSmppServerWorker(new TestConfigurationProvider(), routerQueue);
        StandardMessage message = messagePart(null, "hello", null);
        SubmitSm submitSm = new SubmitSm();
        InEvent<StandardMessage> event = new InEvent<>(message, submitSm, 1,
                new Timestamp(System.currentTimeMillis()));
        worker.enqueueIn(event);
        InEvent<StandardMessage> queued = worker.getInEventQueue().poll();

        try {
            worker.handlePersistedMessages(List.of(queued));

            assertThat(worker.outgoingPdus).singleElement()
                    .satisfies(pdu -> assertThat(pdu.getCommandStatus()).isEqualTo(SmppConstants.STATUS_SYSERR));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void failedAggregatePersistenceRequeuesWithoutAnotherClientResponse() {
        TestSmppServerWorker worker = new TestSmppServerWorker(new TestConfigurationProvider(), new Queue<>());
        StandardMessage aggregate = messagePart(null, "Hello World", "part-1");
        aggregate.reassembledParts = new ArrayList<>(List.of("part-1", "part-2"));
        InEvent<StandardMessage> event = new InEvent<>(aggregate, null, 1,
                new Timestamp(System.currentTimeMillis()));

        worker.handleMessagePersistenceFailure(List.of(event));

        assertThat(worker.getInEventQueue()).containsExactly(event);
        assertThat(worker.outgoingPdus).isEmpty();
    }

    @Test
    void multipartPartIsBufferedAndAcknowledgedOnlyAfterProvisionalPersistence() throws Exception {
        Queue<StandardMessage> routerQueue = new Queue<>();
        TestSmppServerWorker worker = new TestSmppServerWorker(new TestConfigurationProvider(), routerQueue);
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        worker.setMessagePartsHandler(new MessagePartsHandler<>(
                worker.new CcatMessagePartsEventsListener(), TimeUnit.SECONDS.toMillis(30), executor));
        StandardMessage part = messagePart("0500037F0201", "Hello ", null);
        SubmitSm submitSm = new SubmitSm();
        InEvent<StandardMessage> event = new InEvent<>(part, submitSm, 1,
                new Timestamp(System.currentTimeMillis()));

        try {
            worker.enqueueIn(event);
            InEvent<StandardMessage> queued = worker.getInEventQueue().poll();
            assertThat(worker.outgoingPdus).isEmpty();

            worker.handlePersistedMessages(List.of(queued));

            assertThat(routerQueue.dequeue(10)).isNull();
            assertThat(worker.outgoingPdus).singleElement()
                    .satisfies(pdu -> assertThat(pdu.getCommandStatus()).isEqualTo(SmppConstants.STATUS_OK));
        } finally {
            executor.shutdownNow();
        }
    }

    private static StandardMessage messagePart(String udh, String body, String serial) {
        StandardMessage message = new StandardMessage();
        message.owner_id = "account-a";
        message.systemId = "system-a";
        message.from = "sender";
        message.to = "306900000001";
        message.type = StandardMessage.MSG_TEXT;
        message.binheader = udh;
        message.body = body;
        message.serial = serial;
        message.ctstamp = System.currentTimeMillis();
        return message;
    }

    private static class TestSmppServerWorker extends SmppServerWorker<StandardMessage> {
        private final List<StandardMessage> workerQueueMessages = new ArrayList<>();
        private final List<Pdu> outgoingPdus = new ArrayList<>();

        TestSmppServerWorker(SendiumConfigurationProvider configurationProvider, Queue<StandardMessage> routerQueue) {
            super(configurationProvider, "smpp", routerQueue);
        }

        @Override
        public void enqueue(StandardMessage pMsg) {
            workerQueueMessages.add(pMsg);
        }

        @Override
        public void enqueueOut(Pdu event) {
            outgoingPdus.add(event);
        }

    }

    private static class TestConfigurationProvider implements SendiumConfigurationProvider {
        private final Map<String, String> props = new HashMap<>();

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
        public String setProperty(String s, String value) {
            return props.put(s, value);
        }
    }
}
