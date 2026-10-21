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

/** One transceiver session; bounded dispatch keeps socket writes off the scheduler; responses use callbacks. */
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
    private final ThreadPoolExecutor dispatcher;
    private final ScheduledThreadPoolExecutor timeouts;
    private final Semaphore receiptSlots;
    private final java.util.concurrent.atomic.AtomicInteger receiptAcknowledgements = new java.util.concurrent.atomic.AtomicInteger();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> early = new HashMap<>();
    private final EnumMap<Problem, AtomicLong> problems = new EnumMap<>(Problem.class);
    private final Object correlation = new Object();
    private int bufferedReceipts;
    private boolean ambiguousAdmissionObserved;
    private volatile SmppSession session;
    private boolean closed;
    private volatile boolean disconnected;

    private final class Pending {
        final String token;
        final CompletableFuture<MessageLedger.Admission> completion = new CompletableFuture<>();
        final Runnable dispatch = () -> submitRequest(this);
        volatile ScheduledFuture<?> timeout;
        volatile SubmitSm request;
        Pending(String token) { this.token = token; }
    }

    public SmppAdmissionClient(RunConfig config, SmppIngressSettings settings, MessageLedger ledger) {
        this(config, settings, ledger, Thread.ofPlatform().name("smpp-admission-dispatch").factory());
    }

    SmppAdmissionClient(RunConfig config, SmppIngressSettings settings, MessageLedger ledger, ThreadFactory dispatchThreads) {
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
        dispatcher = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.maxOutstandingRequests()),
                dispatchThreads, new ThreadPoolExecutor.AbortPolicy());
        timeouts = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().name("smpp-admission-timeouts").factory());
        timeouts.setRemoveOnCancelPolicy(true);
        receiptSlots = new Semaphore(settings.windowSize());
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
            @Override public void fireExpectedPduResponseReceived(PduAsyncResponse response) {
                if (response.getRequest().getReferenceObject() instanceof Pending admission) {
                    admissionResponse(admission, response.getResponse());
                }
            }
            @Override public void firePduRequestExpired(PduRequest request) {
                if (request.getReferenceObject() instanceof Pending admission) { finish(admission, TIMED_OUT, null, null); }
            }
            @Override public void fireChannelUnexpectedlyClosed() {
                disconnected = true;
                pending.values().forEach(admission -> finish(admission, FAILED, null, Problem.TRANSPORT_FAILURE));
            }
            @Override public PduResponse firePduRequestReceived(PduRequest request) {
                if (!(request instanceof DeliverSm receipt)) { return request.createResponse(); }
                PduResponse response = request.createResponse();
                if (!receiptSlots.tryAcquire()) { problem(Problem.SATURATION); response.setCommandStatus(SmppConstants.STATUS_THROTTLED); return response; }
                receiptAcknowledgements.incrementAndGet();
                response.setCommandStatus(receive(receipt, System.nanoTime()));
                try {
                    tasks.submit(() -> {
                        try {
                            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.connectTimeoutMillis());
                            while (session == null && System.nanoTime() < deadline) { Thread.sleep(1); }
                            if (session != null) { session.sendResponsePdu(response); }
                            else { problem(Problem.TRANSPORT_FAILURE); }
                        } catch (Exception exception) { problem(Problem.TRANSPORT_FAILURE); }
                        finally { receiptAcknowledgements.decrementAndGet(); receiptSlots.release(); }
                    });
                } catch (RejectedExecutionException exception) {
                    receiptAcknowledgements.decrementAndGet(); receiptSlots.release();
                    problem(Problem.TRANSPORT_FAILURE); response.setCommandStatus(SmppConstants.STATUS_SYSERR); return response;
                }
                return null;
            }
        });
    }

    public boolean isBound() { return session != null && session.isBound(); }

    public synchronized Optional<Submission> submit(boolean measured) {
        return submit(measured, System.nanoTime());
    }

    public synchronized Optional<Submission> submit(boolean measured, long scheduledNanos) {
        if (closed || disconnected || !isBound()) { throw new IllegalStateException("SMPP client is not bound"); }
        String token = ledger.attempt(measured, scheduledNanos);
        if (token == null) { return Optional.empty(); }
        Pending admission = new Pending(token);
        pending.put(token, admission);
        try {
            admission.timeout = timeouts.schedule(() -> finish(admission, TIMED_OUT, null, null),
                    config.admissionTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (admission.completion.isDone()) { admission.timeout.cancel(false); }
            dispatcher.execute(admission.dispatch);
        } catch (RejectedExecutionException exception) {
            finish(admission, FAILED, null, Problem.TRANSPORT_FAILURE);
        }
        // A disconnect can race the map insertion after its callback has inspected pending requests.
        if (disconnected) { finish(admission, FAILED, null, Problem.TRANSPORT_FAILURE); }
        return Optional.of(new Submission(token, admission.completion));
    }

    private void submitRequest(Pending admission) {
        if (pending.get(admission.token) != admission) { return; }
        try {
            SubmitSm submit = new SubmitSm();
            submit.setSourceAddress(new Address(SmppConstants.TON_UNKNOWN, SmppConstants.NPI_UNKNOWN, settings.source()));
            submit.setDestAddress(new Address(SmppConstants.TON_UNKNOWN, SmppConstants.NPI_UNKNOWN, settings.destination()));
            submit.setDataCoding(SmppConstants.DATA_CODING_DEFAULT);
            submit.setRegisteredDelivery(config.dlrEnabled()
                    ? SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED : 0);
            submit.setShortMessage(admission.token.getBytes(StandardCharsets.US_ASCII));
            submit.setReferenceObject(admission);
            admission.request = submit;
            if (pending.get(admission.token) != admission) { return; }
            boolean frozen;
            synchronized (ledger) {
                frozen = ledger.isFrozen();
                if (!frozen && pending.get(admission.token) == admission) { ledger.markDispatched(admission.token); }
            }
            if (frozen) { finish(admission, PENDING, null, null); return; }
            if (pending.get(admission.token) != admission) { return; }
            var window = session.sendRequestPdu(submit, config.admissionTimeoutMillis(), false);
            // Response/timeout/close can win before the socket write returns.
            if (pending.get(admission.token) != admission) { window.cancel(); }
        } catch (SmppTimeoutException exception) {
            finish(admission, TIMED_OUT, null, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            finish(admission, FAILED, null, Problem.TRANSPORT_FAILURE);
        } catch (Exception exception) {
            finish(admission, FAILED, null, Problem.TRANSPORT_FAILURE);
        }
    }

    private void admissionResponse(Pending admission, PduResponse pdu) {
        MessageLedger.Admission outcome;
        String id = null;
        Problem failure = null;
        if (pdu instanceof SubmitSmResp response) {
            if (response.getCommandStatus() == SmppConstants.STATUS_OK) {
                id = response.getMessageId();
                if (id == null || id.isBlank() || id.length() > 64) {
                    outcome = FAILED;
                    failure = Problem.INVALID_ACKNOWLEDGEMENT;
                    id = null;
                } else { outcome = ACCEPTED; }
            } else {
                outcome = REJECTED;
                if (response.getCommandStatus() != SmppConstants.STATUS_THROTTLED
                        && response.getCommandStatus() != SmppConstants.STATUS_MSGQFUL) { failure = Problem.UNEXPECTED_STATUS; }
            }
        } else {
            outcome = FAILED;
            failure = Problem.INVALID_ACKNOWLEDGEMENT;
        }
        finish(admission, outcome, id, failure);
    }

    private void finish(Pending admission, MessageLedger.Admission outcome, String id, Problem failure) {
        try {
            synchronized (correlation) {
                if (!pending.remove(admission.token, admission)) { return; }
                if (admission.timeout != null) { admission.timeout.cancel(false); }
                dispatcher.remove(admission.dispatch);
                cancelWindow(admission);
                if (failure != null) { problem(failure); }
                if (outcome == FAILED || outcome == TIMED_OUT) { ambiguousAdmissionObserved = true; }
                synchronized (ledger) {
                    if (ledger.isFrozen()) { outcome = PENDING; }
                    else { ledger.admission(admission.token, outcome, id); }
                }
                List<Long> receipts = id == null ? null : early.remove(id);
                if (receipts != null) {
                    bufferedReceipts -= receipts.size();
                    for (long arrival : receipts) { recordReceipt(admission.token, id, arrival); }
                }
                admission.completion.complete(outcome);
            }
        } catch (RuntimeException exception) {
            admission.completion.completeExceptionally(exception);
        }
    }

    private void cancelWindow(Pending admission) {
        SubmitSm request = admission.request;
        if (session != null && request != null && request.hasSequenceNumberAssigned()) {
            var window = session.getSendWindow().get(request.getSequenceNumber());
            if (window != null && window.getRequest() == request) { window.cancel(); }
        }
    }

    private int receive(DeliverSm receipt, long arrival) {
        if (ledger.isFrozen()) { return SmppConstants.STATUS_SYSERR; }
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
        MessageLedger.ReceiptResult result;
        synchronized (ledger) {
            if (ledger.isFrozen()) { return SmppConstants.STATUS_SYSERR; }
            result = ledger.receiveReceiptAt(token, id, arrival);
        }
        if (result == MessageLedger.ReceiptResult.BEYOND_RETENTION) { problem(Problem.LIMITED_HISTORY); }
        if (result == MessageLedger.ReceiptResult.INVALID) {
            problem(Problem.CORRELATION_FAILURE);
            return SmppConstants.STATUS_INVMSGID;
        }
        return SmppConstants.STATUS_OK;
    }

    /** Invoke only after generation has stopped and pending admissions have completed, before ledger finalization. */
    public void finalizeReceipts() {
        synchronized (correlation) {
            if (ledger.isFrozen()) { early.clear(); bufferedReceipts = 0; return; }
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
    public int pendingReceiptAcknowledgements() { return receiptAcknowledgements.get(); }
    public int bufferedReceipts() { synchronized (correlation) { return bufferedReceipts; } }
    private void problem(Problem problem) { if (!ledger.isFrozen()) { problems.get(problem).incrementAndGet(); } }
    public Map<Problem, Long> problems() {
        Map<Problem, Long> result = new EnumMap<>(Problem.class);
        problems.forEach((key, value) -> result.put(key, value.get()));
        return Map.copyOf(result);
    }

    @Override public void close() {
        synchronized (this) { if (closed) { return; } closed = true; }
        dispatcher.shutdownNow();
        timeouts.shutdownNow();
        pending.values().forEach(admission -> finish(admission, FAILED, null, Problem.TRANSPORT_FAILURE));
        if (session != null) { session.destroy(); }
        tasks.shutdownNow();
        try {
            if (!dispatcher.awaitTermination(settings.connectTimeoutMillis(), TimeUnit.MILLISECONDS)) {
                problem(Problem.TRANSPORT_FAILURE);
            }
            if (!tasks.awaitTermination(settings.connectTimeoutMillis(), TimeUnit.MILLISECONDS)) {
                problem(Problem.TRANSPORT_FAILURE);
            }
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        client.destroy(0, settings.connectTimeoutMillis());
        group.shutdownGracefully(0, settings.connectTimeoutMillis(), TimeUnit.MILLISECONDS).syncUninterruptibly();
        if (pending.isEmpty()) { finalizeReceipts(); }
    }
}
