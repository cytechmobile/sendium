package gr.cytech.sendium.performance;

import com.cloudhopper.smpp.*;
import com.cloudhopper.smpp.impl.DefaultSmppServer;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.*;
import com.cloudhopper.smpp.type.SmppProcessingException;
import com.cloudhopper.smpp.type.Address;
import io.netty.channel.nio.NioEventLoopGroup;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static gr.cytech.sendium.performance.MessageLedger.Event.*;

/** Successful, single-part provider; exceptional responses diagnose invalid tests or saturation. */
public final class SmppProvider implements AutoCloseable {
    public enum Problem { SATURATION, INVALID_SUBMISSION, UNKNOWN_CORRELATION, IO_FAILURE, RECEIPT_REJECTED }
    public record Snapshot(long pending, long peakPending, Map<Problem, Long> problems) { }

    private static final DateTimeFormatter RECEIPT_DATE = DateTimeFormatter.ofPattern("yyMMddHHmm");
    private final RunConfig workload;
    private final ProviderSettings settings;
    private final MessageLedger ledger;
    private final DefaultSmppServer server;
    private final NioEventLoopGroup boss = new NioEventLoopGroup(1);
    private final NioEventLoopGroup worker = new NioEventLoopGroup(2);
    private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore capacity;
    private final CountDownLatch bound = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong messageSequence = new AtomicLong();
    private final AtomicLong peakPending = new AtomicLong();
    private final EnumMap<Problem, AtomicLong> problems = new EnumMap<>(Problem.class);

    public SmppProvider(RunConfig workload, ProviderSettings settings, MessageLedger ledger) {
        this.workload = workload;
        this.settings = settings;
        this.ledger = ledger;
        capacity = new Semaphore(settings.maxPending());
        for (Problem problem : Problem.values()) { problems.put(problem, new AtomicLong()); }
        SmppServerConfiguration config = new SmppServerConfiguration();
        config.setName("performance-provider");
        config.setHost(settings.host());
        config.setPort(settings.port());
        config.setMaxConnectionSize(settings.maxConnections());
        config.setDefaultWindowSize(settings.windowSize());
        config.setDefaultWindowWaitTimeout(settings.ioTimeoutMillis());
        config.setBindTimeout(settings.ioTimeoutMillis());
        server = new DefaultSmppServer(config, new SmppServerHandler() {
            @Override public void sessionBindRequested(Long id, SmppSessionConfiguration configuration,
                    BaseBind request) throws SmppProcessingException {
                if (!settings.systemId().equals(request.getSystemId())) {
                    throw new SmppProcessingException(SmppConstants.STATUS_INVSYSID);
                }
                if (!settings.password().equals(request.getPassword())) {
                    throw new SmppProcessingException(SmppConstants.STATUS_INVPASWD);
                }
                if (configuration.getType() == SmppBindType.RECEIVER
                        || workload.dlrEnabled() && configuration.getType() != SmppBindType.TRANSCEIVER) {
                    throw new SmppProcessingException(SmppConstants.STATUS_INVBNDSTS);
                }
                configuration.setName("performance-provider-" + id);
                configuration.setWriteTimeout(settings.ioTimeoutMillis());
                configuration.getLoggingOptions().setLogPdu(false);
                configuration.getLoggingOptions().setLogBytes(false);
            }

            @Override public void sessionCreated(Long id, SmppServerSession session, BaseBindResp response) {
                session.serverReady(handler(session));
                bound.countDown();
            }

            @Override public void sessionDestroyed(Long id, SmppServerSession session) { }
        }, null, boss, worker);
    }

    public void start() throws Exception {
        if (closed.get()) { throw new IllegalStateException("Provider is closed"); }
        server.start();
    }

    public int port() {
        return settings.port();
    }

    public boolean awaitBound(Duration timeout) throws InterruptedException {
        return bound.await(timeout.toNanos(), TimeUnit.NANOSECONDS) && server.getSessionSize() > 0;
    }

    private DefaultSmppSessionHandler handler(SmppSession session) {
        return new DefaultSmppSessionHandler() {
            @Override public PduResponse firePduRequestReceived(PduRequest request) {
                if (!(request instanceof SubmitSm submit)) { return request.createResponse(); }
                SubmitSmResp response = submit.createResponse();
                if (ledger.isFrozen()) { response.setCommandStatus(SmppConstants.STATUS_SYSERR); return response; }
                if (!validSubmission(submit)) {
                    problem(Problem.INVALID_SUBMISSION);
                    response.setCommandStatus(SmppConstants.STATUS_INVOPTPARAMVAL);
                    return response;
                }
                String token = new String(submit.getShortMessage(), StandardCharsets.US_ASCII);
                boolean accepted;
                synchronized (ledger) { accepted = !ledger.isFrozen() && ledger.acceptProviderArrival(token); }
                if (!accepted) {
                    problem(Problem.UNKNOWN_CORRELATION);
                    response.setCommandStatus(SmppConstants.STATUS_INVMSGID);
                    return response;
                }
                if (closed.get() || !capacity.tryAcquire()) {
                    problem(Problem.SATURATION);
                    response.setCommandStatus(SmppConstants.STATUS_THROTTLED);
                    return response;
                }
                peakPending.accumulateAndGet(settings.maxPending() - capacity.availablePermits(), Math::max);
                String messageId = "p-" + Long.toUnsignedString(messageSequence.incrementAndGet(), 36);
                response.setMessageId(messageId);
                try {
                    tasks.submit(() -> process(session, submit, response, token));
                } catch (java.util.concurrent.RejectedExecutionException exception) {
                    capacity.release();
                    problem(Problem.IO_FAILURE);
                    response.setCommandStatus(SmppConstants.STATUS_SYSERR);
                    return response;
                }
                // Cloudhopper permits responding asynchronously; never sleep on a network event loop.
                return null;
            }
        };
    }

    private boolean validSubmission(SubmitSm submit) {
        int requested = submit.getRegisteredDelivery() & SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_MASK;
        byte[] payload = submit.getShortMessage();
        return payload != null && payload.length > 0 && payload.length <= 160
                && (submit.getDataCoding() == SmppConstants.DATA_CODING_DEFAULT
                    || submit.getDataCoding() == SmppConstants.DATA_CODING_IA5)
                && (submit.getEsmClass() & SmppConstants.ESM_CLASS_UDHI_MASK) == 0
                && !submit.hasOptionalParameter(SmppConstants.TAG_SAR_MSG_REF_NUM)
                && !submit.hasOptionalParameter(SmppConstants.TAG_SAR_TOTAL_SEGMENTS)
                && !submit.hasOptionalParameter(SmppConstants.TAG_SAR_SEGMENT_SEQNUM)
                && !submit.hasOptionalParameter(SmppConstants.TAG_MESSAGE_PAYLOAD)
                && requested == (workload.dlrEnabled()
                    ? SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED : 0);
    }

    private void process(SmppSession session, SubmitSm submit, SubmitSmResp response, String token) {
        try {
            Thread.sleep(workload.providerResponseDelayMillis());
            session.sendResponsePdu(response);
            synchronized (ledger) { if (ledger.isFrozen()) { return; } ledger.event(token, PROVIDER_RESPONSE); }
            if (workload.dlrEnabled()) {
                Thread.sleep(workload.providerDlrDelayMillis());
                DeliverSm receipt = receipt(submit, response.getMessageId());
                synchronized (ledger) { if (ledger.isFrozen()) { return; } ledger.event(token, RECEIPT_GENERATED); }
                var future = session.sendRequestPdu(receipt, settings.ioTimeoutMillis(), true);
                if (!future.await(settings.ioTimeoutMillis()) || !future.isSuccess()) {
                    problem(Problem.IO_FAILURE);
                    session.destroy();
                } else if (future.getResponse().getCommandStatus() != SmppConstants.STATUS_OK) {
                    problem(Problem.RECEIPT_REJECTED);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (!closed.get()) { problem(Problem.IO_FAILURE); }
        } catch (Exception exception) {
            if (!closed.get()) { problem(Problem.IO_FAILURE); }
        } finally {
            capacity.release();
        }
    }

    private DeliverSm receipt(SubmitSm submit, String messageId) throws Exception {
        DeliverSm receipt = new DeliverSm();
        receipt.setDataCoding(SmppConstants.DATA_CODING_DEFAULT);
        receipt.setEsmClass(SmppConstants.ESM_CLASS_MT_SMSC_DELIVERY_RECEIPT);
        receipt.setSourceAddress(copy(submit.getDestAddress()));
        receipt.setDestAddress(copy(submit.getSourceAddress()));
        String date = RECEIPT_DATE.format(ZonedDateTime.now(ZoneOffset.UTC));
        String body = "id:" + messageId + " sub:001 dlvrd:001 submit date:" + date
                + " done date:" + date + " stat:DELIVRD err:000 text:benchmark";
        receipt.setShortMessage(body.getBytes(StandardCharsets.US_ASCII));
        return receipt;
    }

    private static Address copy(Address address) {
        return new Address(address.getTon(), address.getNpi(), address.getAddress());
    }

    private void problem(Problem problem) { if (!ledger.isFrozen()) { problems.get(problem).incrementAndGet(); } }

    public Snapshot snapshot() {
        Map<Problem, Long> counts = new EnumMap<>(Problem.class);
        problems.forEach((key, value) -> counts.put(key, value.get()));
        return new Snapshot(settings.maxPending() - capacity.availablePermits(), peakPending.get(), Map.copyOf(counts));
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) { return; }
        tasks.shutdownNow();
        try {
            tasks.awaitTermination(settings.ioTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                server.destroy(0, settings.ioTimeoutMillis());
            } finally {
                boss.shutdownGracefully(0, settings.ioTimeoutMillis(), TimeUnit.MILLISECONDS).syncUninterruptibly();
                worker.shutdownGracefully(0, settings.ioTimeoutMillis(), TimeUnit.MILLISECONDS).syncUninterruptibly();
            }
        }
    }
}
