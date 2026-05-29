package gr.cytech.sendium.core.smpp.server;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.pdu.DeliverSm;
import gr.cytech.sendium.conf.PropertyChangeListener;
import gr.cytech.sendium.conf.SendiumConfigurationProvider;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.queue.Queue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SmppServerWorkerReassemblyTest {

    @Test
    void completeUdhPartsAreReassembledAndRoutedToRouterQueue() throws Exception {
        Queue<StandardMessage> routerQueue = new Queue<>();
        TestSmppServerWorker worker = new TestSmppServerWorker(new TestConfigurationProvider(), routerQueue);
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        MessagePartsHandler<StandardMessage> handler = new MessagePartsHandler<>(
                worker.new CcatMessagePartsEventsListener(), TimeUnit.SECONDS.toMillis(30), executor);

        try {
            handler.addMessagePart(messagePart("0500037F0202", "World", "part-2"));
            handler.addMessagePart(messagePart("0500037F0201", "Hello ", "part-1"));

            StandardMessage routed = routerQueue.dequeue(1_000);

            assertThat(routed).isNotNull();
            assertThat(routed.body).isEqualTo("Hello World");
            assertThat(routed.binheader).isNull();
            assertThat(routed.reassembledParts).containsExactly("part-1", "part-2");
            assertThat(worker.workerQueueMessages).isEmpty();

            InEvent<StandardMessage> persisted = worker.getInEventQueue().poll(1_000, TimeUnit.MILLISECONDS);
            assertThat(persisted).isNotNull();
            assertThat(persisted.pMsg).isSameAs(routed);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void delayedUdhPartsAreRoutedToRouterQueueWithoutBecomingDeliverSm() throws Exception {
        Queue<StandardMessage> routerQueue = new Queue<>();
        TestSmppServerWorker worker = new TestSmppServerWorker(new TestConfigurationProvider(), routerQueue);
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

            InEvent<StandardMessage> persisted = worker.getInEventQueue().poll(1_000, TimeUnit.MILLISECONDS);
            assertThat(persisted).isNotNull();
            assertThat(persisted.pMsg).isSameAs(part);
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

        TestSmppServerWorker(SendiumConfigurationProvider configurationProvider, Queue<StandardMessage> routerQueue) {
            super(configurationProvider, "smpp", routerQueue);
        }

        @Override
        public void enqueue(StandardMessage pMsg) {
            workerQueueMessages.add(pMsg);
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
