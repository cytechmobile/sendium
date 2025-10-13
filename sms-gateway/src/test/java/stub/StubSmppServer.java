package stub;

import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppServerConfiguration;
import com.cloudhopper.smpp.SmppServerHandler;
import com.cloudhopper.smpp.SmppServerSession;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.SmppSessionHandler;
import com.cloudhopper.smpp.impl.DefaultSmppServer;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.BaseBind;
import com.cloudhopper.smpp.pdu.BaseBindResp;
import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.SmppChannelException;
import com.cloudhopper.smpp.type.SmppProcessingException;
import io.netty.channel.nio.NioEventLoopGroup;
import io.quarkus.runtime.logging.LoggingSetupRecorder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Scanner;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StubSmppServer {
    public static final int SERVER_PORT = 2778;
    public static final int SERVERS = 1;
    public static final int BOSS_THREADS = 5;
    public static final int WORKER_THREADS = 5;
    public static final int SCHEDULER_THREADS = 5;
    public static final int DLR_DELAY_MILLIS = 1000;
    public static final StubSmppServer[] servers = new StubSmppServer[SERVERS];
    public static final Set<SmppSession> sessions =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Logger logger = LoggerFactory.getLogger(StubSmppServer.class);
    final int port;

    DefaultSmppServer server;
    SmppServerConfiguration configuration;
    SmppServerHandler bindHandler;
    SmppSessionHandler sessionHandler;
    ScheduledExecutorService scheduler;

    public StubSmppServer(int serverPort) {
        this.port = serverPort;
        var c = new SmppServerConfiguration();
        c.setDefaultWindowSize(1000);
        c.setPort(serverPort);
        scheduler = Executors.newScheduledThreadPool(SCHEDULER_THREADS);
        sessionHandler =
                new DefaultSmppSessionHandler() {
                    @Override
                    public PduResponse firePduRequestReceived(PduRequest pduRequest) {
                        logger.warn("Received request PDU: port:{} : {}", port, pduRequest);
                        if (!(pduRequest instanceof SubmitSm)) {
                            return pduRequest.createResponse();
                        }
                        SubmitSmResp resp = (SubmitSmResp) pduRequest.createResponse();
                        String responseId = UUID.randomUUID().toString();
                        resp.setMessageId(responseId);
                        scheduler.schedule(
                                () -> {
                                    try {
                                        DeliverSm dsm = new DeliverSm();
                                        dsm.setDataCoding(SmppConstants.DATA_CODING_DEFAULT);
                                        dsm.setEsmClass(
                                                SmppConstants.ESM_CLASS_MT_SMSC_DELIVERY_RECEIPT);
                                        dsm.setDestAddress(
                                                ((SubmitSm) pduRequest).getDestAddress());
                                        String dlr =
                                                "id:"
                                                        + responseId
                                                        + " sub:001 dlvrd:001 submit date:1901232335 done date:1901232335 stat:DELIVRD err:000 IMSI:240084709991339 MSC:467080";
                                        dsm.setShortMessage(dlr.getBytes(StandardCharsets.UTF_8));
                                        logger.info("{} sending dlr: {}", port, dlr);
                                        for (var s : sessions) {
                                            if (!s.isBound()) {
                                                sessions.remove(s);
                                                continue;
                                            }
                                            if (s.getBindType() == SmppBindType.TRANSMITTER) {
                                                continue;
                                            }
                                            s.sendRequestPdu(dsm, 30_000, true);
                                            return;
                                        }
                                        logger.warn(
                                                "{} no session found to send dlr! {}",
                                                port,
                                                sessions);
                                    } catch (Exception e) {
                                        logger.error("{} error sending dlr", port, e);
                                    }
                                },
                                DLR_DELAY_MILLIS,
                                TimeUnit.MILLISECONDS);
                        return resp;
                    }
                };

        bindHandler =
                new SmppServerHandler() {
                    @Override
                    public void sessionBindRequested(
                            Long sessionId,
                            SmppSessionConfiguration sessionConfiguration,
                            BaseBind bindRequest)
                            throws SmppProcessingException {
                        logger.info("port:{} bind requested: {}", port, bindRequest);
                    }

                    @Override
                    public void sessionCreated(
                            Long sessionId,
                            SmppServerSession session,
                            BaseBindResp preparedBindResponse)
                            throws SmppProcessingException {
                        sessions.add(session);
                        session.serverReady(sessionHandler);
                    }

                    @Override
                    public void sessionDestroyed(Long sessionId, SmppServerSession session) {
                        logger.info("session destroyed on port:{}", port);
                        sessions.remove(session);
                    }
                };

        server =
                new DefaultSmppServer(
                        c,
                        bindHandler,
                        null,
                        new NioEventLoopGroup(BOSS_THREADS),
                        new NioEventLoopGroup(WORKER_THREADS));
    }

    public static void startServer(int idx) {
        stopServer(idx);
        servers[idx] = new StubSmppServer(SERVER_PORT + idx);
        try {
            servers[idx].start();
            logger.info("started server at: {}", SERVER_PORT + idx);
        } catch (Exception e) {
            logger.warn("error starting server {}", idx, e);
        }
    }

    public static void stopServer(int idx) {
        if (servers[idx] == null) {
            return;
        }
        servers[idx].destroy();
        servers[idx] = null;
    }

    public static void startStopServer(int idx) {
        if (servers[idx] == null) {
            startServer(idx);
            return;
        }

        stopServer(idx);
    }

    public static void commands() throws Exception {
        Scanner s = new Scanner(System.in);
        String c;
        while (!"e".equals(c = s.nextLine())) {
            if ("a".equals(c)) {
                startStopServer(0);
            } else if ("s".equals(c)) {
                startStopServer(1);
            } else if ("d".equals(c)) {
                startServer(2);
            } else {
                logger.warn("unknown command: {}", c);
            }
        }
    }

    public void start() throws SmppChannelException {
        server.start();
    }

    public void destroy() {
        server.destroy(0, 100);
    }

    public static void fixLogging() throws Exception {
        System.setProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager");
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Class<?> lrs = cl.loadClass(LoggingSetupRecorder.class.getName());
        lrs.getDeclaredMethod("handleFailedStart").invoke((Object) null);
    }

    public static void main(String[] args) throws Exception {
        fixLogging();
        IntStream.range(0, SERVERS).forEach(StubSmppServer::startServer);
        commands();
        IntStream.range(0, SERVERS).forEach(StubSmppServer::stopServer);
    }
}
