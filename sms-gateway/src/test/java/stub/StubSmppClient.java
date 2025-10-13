package stub;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.commons.util.HexUtil;
import com.cloudhopper.commons.util.windowing.WindowFuture;
import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.SmppSessionHandler;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.tlv.Tlv;
import com.cloudhopper.smpp.type.Address;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.channel.nio.NioEventLoopGroup;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StubSmppClient {
    public static final int SERVER_PORT = 2776;
    public static final String SERVER_HOST = "localhost";
    public static final String USERNAME = "smppclient2";
    public static final String PASSWORD = "password";
    public static final AtomicLong count = new AtomicLong();
    private static final Logger logger = LoggerFactory.getLogger(StubSmppClient.class);
    DefaultSmppClient client;
    SmppSessionConfiguration config;
    SmppSessionHandler sessionHandler;
    SmppSession session;

    public StubSmppClient() throws Exception {
        client =
                new DefaultSmppClient(
                        new NioEventLoopGroup(
                                0,
                                new ThreadFactoryBuilder()
                                        .setNameFormat("client-nio-elg-%d")
                                        .setDaemon(true)
                                        .build()));
        config = new SmppSessionConfiguration(SmppBindType.TRANSCEIVER, USERNAME, PASSWORD);
        config.setHost(SERVER_HOST);
        config.setPort(SERVER_PORT);
        config.setWindowSize(1000);
        config.setUseSsl(SERVER_PORT == 2776 || SERVER_PORT == 27778);
        sessionHandler =
                new DefaultSmppSessionHandler() {
                    @Override
                    public PduResponse firePduRequestReceived(PduRequest pduRequest) {
                        logger.info("Received request: {}", pduRequest);
                        return pduRequest.createResponse();
                    }
                };
    }

    public static WindowFuture<Integer, PduRequest, PduResponse> sendSms(
            StubSmppClient cl, String from, String to) throws Exception {
        SubmitSm submit = new SubmitSm();
        submit.setSourceAddress(new Address((byte) 0, (byte) 0, from));
        submit.setDestAddress(new Address((byte) 0, (byte) 0, to));
        submit.setRegisteredDelivery(SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED);
        submit.setDataCoding(SmppConstants.DATA_CODING_DEFAULT);
        submit.setShortMessage(("test sms: " + count.incrementAndGet()).getBytes());
        return cl.session.sendRequestPdu(submit, 10_000, false);
    }

    public static void sendConcSMS(StubSmppClient cl, String from, String to) throws Exception {
        List<SubmitSmResp> resps = new ArrayList<>();
        for (int i = 0; i < SmsData.LATIN1_BODY_LONG_PARTS.length; i++) {
            // send parts
            SubmitSmResp submitSmResp =
                    sendSubmitSm(
                            cl.session,
                            from,
                            to,
                            SmsData.LATIN1_UDH_LONG_PARTS[i],
                            SmsData.LATIN1_BODY_LONG_PARTS[i],
                            SmsData.ENCODING_LATIN1,
                            true,
                            null,
                            null,
                            SmppConstants.STATUS_OK);
            resps.add(submitSmResp);
        }
    }

    public static void commands(StubSmppClient cl) throws Exception {
        logger.info("waiting for your command");
        Scanner s = new Scanner(System.in);
        String c;
        while (!"e".equals(c = s.nextLine())) {
            if ("s".equals(c)) {
                var wf = sendSms(cl, "stubclient", "306900000000");
                wf.await(10_000);
            } else if (c.startsWith("c")) {
                sendConcSMS(cl, "stubclient", "306910000000");
            } else if (c.startsWith("m")) {
                var num = Integer.parseInt(c.split(" ")[1]);
                Instant start = Instant.now();
                logger.info("sending multiple sms: {} at: {}", num, start);
                List<WindowFuture> wfs = new ArrayList<>(num);
                for (int i = 0; i < num; i++) {
                    wfs.add(sendSms(cl, "stub" + i, 306900000000L + i + ""));
                }
                logger.info("all sent, now waiting the futures");
                while (!wfs.isEmpty()) {
                    wfs.remove(0).await(10_000);
                }
                Instant end = Instant.now();
                logger.info(
                        "Started sending {} SMS at: {} done at: {}. Total time taken: {}",
                        num,
                        start,
                        end,
                        Duration.between(start, end));
            } else if ("r".equals(c)) {
                cl.destroy();
                cl.start();
            } else {
                logger.warn("unknown command: {}", c);
            }
        }
    }

    public void start() throws Exception {
        session = client.bind(config, sessionHandler);
    }

    public void destroy() {
        client.destroy(0, 0);
    }

    private static SubmitSmResp sendSubmitSm(
            SmppSession clientSession,
            String from,
            String to,
            String udh,
            String body,
            String charset,
            boolean requestDlr,
            String sched,
            Byte dcs,
            int expectedResponseStatus)
            throws Exception {
        SubmitSm submit0 = new SubmitSm();

        // add delivery receipt
        if (requestDlr) {
            submit0.setRegisteredDelivery(SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED);
        } else {
            submit0.setRegisteredDelivery(
                    SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_NOT_REQUESTED);
        }

        submit0.setSourceAddress(new Address((byte) 0x03, (byte) 0x00, from));
        submit0.setDestAddress(new Address((byte) 0x01, (byte) 0x01, to));

        byte[] bodyBytes;
        if (SmsData.ENCODING_BINARY.equals(charset)) {
            submit0.setDataCoding(SmppConstants.DATA_CODING_8BIT);
            bodyBytes = HexUtil.toByteArray(body);
        } else {
            if (SmsData.ENCODING_UCS2.equals(charset)) {
                submit0.setDataCoding(SmppConstants.DATA_CODING_UCS2);
            } else if (SmsData.ENCODING_LATIN1.equals(charset)) {
                submit0.setDataCoding(SmppConstants.DATA_CODING_LATIN1);
            } else {
                submit0.setDataCoding(SmppConstants.DATA_CODING_GSM);
            }
            bodyBytes = CharsetUtil.encode(body, charset);
        }

        if (dcs != null) {
            submit0.setDataCoding(dcs);
        }

        byte[] bytes;
        byte[] udhBytes;
        if (udh != null) {
            submit0.setEsmClass(SmppConstants.ESM_CLASS_UDHI_MASK);
            udhBytes = HexUtil.toByteArray(udh);

            bytes = ArrayUtils.addAll(udhBytes, bodyBytes);
        } else {
            bytes = bodyBytes;
        }

        if (bytes.length <= 255) {
            submit0.setShortMessage(bytes);
        } else {
            Tlv messagePayload = new Tlv(SmppConstants.TAG_MESSAGE_PAYLOAD, bytes);
            submit0.setOptionalParameter(messagePayload);
        }

        if (sched != null) {
            submit0.setScheduleDeliveryTime(sched);
        }

        SubmitSmResp submitSmResp = clientSession.submit(submit0, 10_000);

        return submitSmResp;
    }

    public static void main(String[] args) throws Exception {
        StubSmppServer.fixLogging();
        var c = new StubSmppClient();
        c.start();
        commands(c);
        c.destroy();
    }
}
