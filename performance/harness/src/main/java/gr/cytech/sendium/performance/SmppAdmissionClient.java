package gr.cytech.sendium.performance;

import com.cloudhopper.smpp.*;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.*;
import com.cloudhopper.smpp.type.Address;
import com.cloudhopper.smpp.type.SmppTimeoutException;
import io.netty.channel.nio.NioEventLoopGroup;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import static gr.cytech.sendium.performance.MessageLedger.Admission.*;

/** One transceiver session; bounded virtual tasks keep admission response waits off the scheduler. */
public final class SmppAdmissionClient implements AutoCloseable {
    public enum Problem { INVALID_ACKNOWLEDGEMENT, UNEXPECTED_STATUS, TRANSPORT_FAILURE,
        INVALID_RECEIPT, CORRELATION_FAILURE, SATURATION, UNCORRELATED_RECEIPT, AMBIGUOUS_RECEIPT, LIMITED_HISTORY }
    public record Submission(String token, CompletableFuture<MessageLedger.Admission> completion) { }
    private static final Pattern RECEIPT = Pattern.compile(
            "^id:(\\S+) sub:001 dlvrd:001 submit date:\\d{10,12} done date:\\d{10,12} stat:DELIVRD err:0{1,3} text:.*$",
            Pattern.DOTALL);
    private final RunConfig config;
    private final SmppIngressSettings settings;
    private final MessageLedger ledger;
    private final NioEventLoopGroup group;
    private final DefaultSmppClient client;
    private final ExecutorService tasks;
    private final Map<String, CompletableFuture<MessageLedger.Admission>> pending = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> early = new HashMap<>();
    private final EnumMap<Problem, AtomicLong> problems = new EnumMap<>(Problem.class);
    private final Object correlation = new Object();
    private int bufferedReceipts;
    private boolean ambiguousAdmissionObserved;
    private volatile SmppSession session;
    private boolean closed;

    public SmppAdmissionClient(RunConfig config, SmppIngressSettings settings, MessageLedger ledger) {
        if (!"smpp".equals(config.ingressMode()) || settings.windowSize() < config.maxOutstandingRequests()) {
            // Otherwise waiting for the local SMPP window would inflate application latency.
            throw new IllegalArgumentException("SMPP window must cover outstanding request capacity");
        }
        this.config = config;
        this.settings = settings;
        this.ledger = ledger;
        group = new NioEventLoopGroup(1);
        client = new DefaultSmppClient(group);
        tasks = Executors.newVirtualThreadPerTaskExecutor();
        for (Problem problem : Problem.values()) { problems.put(problem, new AtomicLong()); }
    }

    public synchronized void connect() throws Exception {
        if (closed || session != null) { throw new IllegalStateException("Client already connected or closed"); }
        SmppSessionConfiguration connection = new SmppSessionConfiguration(
                SmppBindType.TRANSCEIVER, settings.systemId(), settings.password());
        connection.setHost(settings.host());
        connection.setPort(settings.port());
        connection.setWindowSize(settings.windowSize());
        connection.setConnectTimeout(settings.connectTimeoutMillis());
        connection.setBindTimeout(settings.connectTimeoutMillis());
        connection.setWriteTimeout(config.admissionTimeoutMillis());
        connection.setWindowWaitTimeout(config.admissionTimeoutMillis());
        connection.getLoggingOptions().setLogPdu(false);
        connection.getLoggingOptions().setLogBytes(false);
        session = client.bind(connection, new DefaultSmppSessionHandler() {
            @Override public PduResponse firePduRequestReceived(PduRequest request) {
                if (!(request instanceof DeliverSm receipt)) { return request.createResponse(); }
                PduResponse response = request.createResponse();
                response.setCommandStatus(receive(receipt, System.nanoTime()));
                return response;
            }
        });
    }

    public boolean isBound() { return session != null && session.isBound(); }

    public synchronized Optional<Submission> submit(boolean measured) {
        return submit(measured, System.nanoTime());
    }

    public synchronized Optional<Submission> submit(boolean measured, long scheduledNanos) {
        if (closed || !isBound()) { throw new IllegalStateException("SMPP client is not bound"); }
        String token = ledger.attempt(measured, scheduledNanos);
        if (token == null) { return Optional.empty(); }
        CompletableFuture<MessageLedger.Admission> completion = new CompletableFuture<>();
        pending.put(token, completion);
        tasks.submit(() -> submitRequest(token, completion));
        return Optional.of(new Submission(token, completion));
    }

    private void submitRequest(String token, CompletableFuture<MessageLedger.Admission> completion) {
        MessageLedger.Admission outcome;
        String id = null;
        try {
            SubmitSm submit = new SubmitSm();
            submit.setSourceAddress(new Address(SmppConstants.TON_UNKNOWN, SmppConstants.NPI_UNKNOWN, settings.source()));
            submit.setDestAddress(new Address(SmppConstants.TON_UNKNOWN, SmppConstants.NPI_UNKNOWN, settings.destination()));
            submit.setDataCoding(SmppConstants.DATA_CODING_DEFAULT);
            submit.setRegisteredDelivery(config.dlrEnabled()
                    ? SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED : 0);
            submit.setShortMessage(token.getBytes(StandardCharsets.US_ASCII));
            ledger.markDispatched(token);
            SubmitSmResp response = session.submit(submit, config.admissionTimeoutMillis());
            if (response.getCommandStatus() == SmppConstants.STATUS_OK) {
                id = response.getMessageId();
                if (id == null || id.isBlank() || id.length() > 64) {
                    outcome = FAILED;
                    problem(Problem.INVALID_ACKNOWLEDGEMENT);
                    id = null;
                } else { outcome = ACCEPTED; }
            } else {
                outcome = REJECTED;
                if (response.getCommandStatus() != SmppConstants.STATUS_THROTTLED
                        && response.getCommandStatus() != SmppConstants.STATUS_MSGQFUL) { problem(Problem.UNEXPECTED_STATUS); }
            }
        } catch (SmppTimeoutException exception) {
            outcome = TIMED_OUT;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            outcome = FAILED;
            problem(Problem.TRANSPORT_FAILURE);
        } catch (Exception exception) {
            outcome = FAILED;
            problem(Problem.TRANSPORT_FAILURE);
        }
        try {
            synchronized (correlation) {
                if (outcome == FAILED || outcome == TIMED_OUT) { ambiguousAdmissionObserved = true; }
                ledger.admission(token, outcome, id);
                List<Long> receipts = id == null ? null : early.remove(id);
                if (receipts != null) {
                    bufferedReceipts -= receipts.size();
                    for (long arrival : receipts) { recordReceipt(token, id, arrival); }
                }
                pending.remove(token);
                completion.complete(outcome);
            }
        } catch (RuntimeException exception) {
            pending.remove(token);
            completion.completeExceptionally(exception);
        }
    }

    private int receive(DeliverSm receipt, long arrival) {
        byte[] body = receipt.getShortMessage();
        if (!config.dlrEnabled() || (receipt.getEsmClass() & SmppConstants.ESM_CLASS_MT_MASK)
                != SmppConstants.ESM_CLASS_MT_SMSC_DELIVERY_RECEIPT
                || body == null || body.length > 254
                || (receipt.getEsmClass() & SmppConstants.ESM_CLASS_UDHI_MASK) != 0
                || !(receipt.getDataCoding() == SmppConstants.DATA_CODING_DEFAULT
                    || receipt.getDataCoding() == SmppConstants.DATA_CODING_IA5)) {
            problem(Problem.INVALID_RECEIPT);
            return SmppConstants.STATUS_INVOPTPARAMVAL;
        }
        var match = RECEIPT.matcher(new String(body, StandardCharsets.US_ASCII));
        if (!match.matches() || match.group(1).length() > 64) {
            problem(Problem.INVALID_RECEIPT);
            return SmppConstants.STATUS_INVOPTPARAMVAL;
        }
        String id = match.group(1);
        synchronized (correlation) {
            String token = ledger.tokenForIngressId(id);
            if (token != null) { return recordReceipt(token, id, arrival); }
            if (bufferedReceipts >= config.maxTrackedMessages()) {
                problem(Problem.SATURATION);
                return SmppConstants.STATUS_THROTTLED;
            }
            early.computeIfAbsent(id, ignored -> new ArrayList<>()).add(arrival);
            bufferedReceipts++;
            return SmppConstants.STATUS_OK;
        }
    }

    private int recordReceipt(String token, String id, long arrival) {
        MessageLedger.ReceiptResult result = ledger.receiveReceiptAt(token, id, arrival);
        if (result == MessageLedger.ReceiptResult.BEYOND_RETENTION) { problem(Problem.LIMITED_HISTORY); }
        if (result == MessageLedger.ReceiptResult.INVALID) {
            problem(Problem.CORRELATION_FAILURE);
            return SmppConstants.STATUS_INVMSGID;
        }
        return SmppConstants.STATUS_OK;
    }

    /** Invoke only after admission tasks have stopped, before ledger finalization. */
    public void finalizeReceipts() {
        synchronized (correlation) {
            if (!pending.isEmpty()) { throw new IllegalStateException("Admission requests still pending"); }
            if (bufferedReceipts > 0) {
                Problem reason = ledger.retiredEvictions() > 0 ? Problem.LIMITED_HISTORY
                        : ambiguousAdmissionObserved ? Problem.AMBIGUOUS_RECEIPT
                        : Problem.UNCORRELATED_RECEIPT;
                problems.get(reason).addAndGet(bufferedReceipts);
                early.clear();
                bufferedReceipts = 0;
            }
        }
    }

    public int pendingRequests() { return pending.size(); }
    public int bufferedReceipts() { synchronized (correlation) { return bufferedReceipts; } }
    private void problem(Problem problem) { problems.get(problem).incrementAndGet(); }
    public Map<Problem, Long> problems() {
        Map<Problem, Long> result = new EnumMap<>(Problem.class);
        problems.forEach((key, value) -> result.put(key, value.get()));
        return Map.copyOf(result);
    }

    @Override public void close() {
        synchronized (this) { if (closed) { return; } closed = true; }
        if (session != null) { session.destroy(); }
        tasks.shutdownNow();
        try {
            if (!tasks.awaitTermination(settings.connectTimeoutMillis(), TimeUnit.MILLISECONDS)) {
                problem(Problem.TRANSPORT_FAILURE);
            }
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        client.destroy(0, settings.connectTimeoutMillis());
        group.shutdownGracefully(0, settings.connectTimeoutMillis(), TimeUnit.MILLISECONDS).syncUninterruptibly();
        if (pending.isEmpty()) { finalizeReceipts(); }
    }
}
