package gr.cytech.sendium.core.smpp.client;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.tlv.Tlv;
import com.cloudhopper.smpp.type.Address;
import gr.cytech.sendium.conf.PropertyChangeListener;
import gr.cytech.sendium.conf.SendiumConfigurationProvider;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.queue.Queue;
import gr.cytech.sendium.core.worker.ForwardMoService;
import gr.cytech.sendium.core.worker.Tracker;
import gr.cytech.sendium.external.WorkerResourceProvider;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class SmppClientWorkerTest {

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
        assertThat(tracker.dlrSmscId).isEqualTo("abc123");
        assertThat(tracker.dlrFrom).isEqualTo("smsc");
        assertThat(tracker.dlrTo).isEqualTo("recipient");
        assertThat(tracker.dlrState).isEqualTo(StandardMessage.DLR_STAT_DELIVRD);
        assertThat(tracker.dlrTlvs).containsEntry("carrier_1400", "network-a");
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
        assertThat(tracker.dlrSmscId).isEqualTo("smsc-2");
        assertThat(tracker.dlrState).isEqualTo(StandardMessage.DLR_STAT_FAILED);
        assertThat(tracker.dlrErrorCode).isEqualTo("7");
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
        private final java.util.List<StandardMessage> success = new java.util.ArrayList<>();
        private final java.util.List<StandardMessage> temporaryFailures = new java.util.ArrayList<>();
        private final java.util.List<StandardMessage> failures = new java.util.ArrayList<>();

        TestSmppClientWorker(SendiumConfigurationProvider configurationProvider,
                             Queue<StandardMessage> routerQueue,
                             Tracker<StandardMessage> tracker) {
            super(configurationProvider, routerQueue, new ScheduledThreadPoolExecutor(1));
            this.messageTracker = tracker;
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
        private String dlrSmscId;
        private String dlrFrom;
        private String dlrTo;
        private int dlrState;
        private String dlrErrorCode;
        private HashMap<String, String> dlrTlvs;

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
        public int updateSendStatusAndExtID(String smsid, StandardMessage pMsg, String smscid) {
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
        public void createAndEnqueueDLR(int mqid, String smscid, String smsid, String from, String to, String body,
                                        int state, String errorCode, HashMap<String, String> tlvs) {
            this.dlrMqId = mqid;
            this.dlrSmscId = smscid;
            this.dlrFrom = from;
            this.dlrTo = to;
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
