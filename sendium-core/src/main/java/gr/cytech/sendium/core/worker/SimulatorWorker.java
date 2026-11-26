package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.conf.SendiumConfigurationProvider;
import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.queue.Queue;
import gr.cytech.sendium.core.smpp.client.ProviderRequestMetrics;
import gr.cytech.sendium.external.filter.FilterException;
import gr.cytech.sendium.external.filter.FilterStatusCodes;
import gr.cytech.sendium.util.MessageTrace;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Explicitly configured test destination. Never connects to a provider or transmits an SMS. */
@Dependent
@WorkerType("simulator")
public class SimulatorWorker extends AbstractOutWorker<StandardMessage> implements OutboundProvider<StandardMessage> {
    @Inject
    ProviderRequestMetrics providerMetrics;

    private final String[][] parameters = {{"outcome", "delivered"}, {"generateDlrs", "true"}};
    private volatile Settings settings = new Settings(Outcome.DELIVERED, true);

    @Override
    public String getType() {
        return "simulator";
    }

    @Override
    public void setupInstance(SendiumConfigurationProvider configuration, String name, Queue<StandardMessage> router) {
        super.setupInstance(configuration, name, router);
        prependInstanceName(parameters);
        configuration.loadDefaultParams(parameters);
        configure();
        logger.warn("simulatorWorker|enabled|worker:{}|outcome:{}|generateDlrs:{}|reason:noRealSmsDelivery",
                MessageTrace.value(getFullName()), settings.outcome(), settings.generateDlrs());
    }

    private void configure() {
        Outcome outcome;
        try {
            outcome = Outcome.valueOf(configurationProvider.getPrpt(parameters[0]).strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IllegalArgumentException("simulatorConfiguration|error|reason:invalidOutcome", failure);
        }
        String generate = configurationProvider.getPrpt(parameters[1]);
        if (!"true".equalsIgnoreCase(generate) && !"false".equalsIgnoreCase(generate)) {
            throw new IllegalArgumentException("simulatorConfiguration|error|reason:invalidGenerateDlrs");
        }
        settings = new Settings(outcome, Boolean.parseBoolean(generate));
    }

    @Override
    protected boolean myPropertyChange(String key, String value, String previous) {
        if (key.equals(parameters[0][0]) || key.equals(parameters[1][0])) {
            configure();
            return true;
        }
        return super.myPropertyChange(key, value, previous);
    }

    @Override
    public Submission submitPrepared(StandardMessage message, Retry<StandardMessage> retry, boolean retryAttempt)
            throws Exception {
        return prepare(message, retry, retryAttempt, null);
    }

    @Override
    public Submission submitPrepared(StandardMessage message, Retry<StandardMessage> retry, boolean retryAttempt,
                                     Consumer<? super StandardMessage> beforeSend) throws Exception {
        return prepare(message, retry, retryAttempt,
                Objects.requireNonNull(beforeSend, "dlrProvider|error|reason:missingPreparationGuard"));
    }

    private Submission prepare(StandardMessage message, Retry<StandardMessage> retry, boolean retryAttempt,
                               Consumer<? super StandardMessage> beforeSend) throws Exception {
        requireRunning();
        applyRateLimit();
        requireRunning();
        message.outgateway = getFullName();
        String originalBody = doCharMap(message);
        try {
            checkBeforeDoMessageFilters(message);
            if (beforeSend != null) {
                beforeSend.accept(message);
            }
            return simulate(message, retryAttempt, true);
        } catch (FilterException failure) {
            message.body = originalBody;
            if (failure.getStatusCode() == FilterStatusCodes.DROP) {
                return new PendingHandoff(message.serial, () -> CompletableFuture.completedStage(null));
            }
            var replacement = (StandardMessage) Objects.requireNonNull(failure.getMessageObj(), "filter replacement");
            replacement.body = originalBody;
            return new PendingHandoff(message.serial, () -> coordinatePreparationFailure(replacement,
                    failure.getStatusCode() != FilterStatusCodes.RETRY, retry::retry));
        } catch (Exception failure) {
            message.body = originalBody;
            throw failure;
        }
    }

    private void requireRunning() {
        if (!keepOnRunning || isPause()) {
            throw new IllegalStateException("simulatorSubmission|error|reason:stoppedOrPaused");
        }
    }

    /** Legacy worker-loop entry point; coordinated standalone dispatch uses submitPrepared instead. */
    @Override
    public StandardMessage doMessage(int threadIndex, StandardMessage message) throws IOException {
        try {
            simulate(message, false, false).firstAttempt.join();
            return null;
        } catch (RuntimeException failure) {
            throw new IOException("simulatorSubmission|error|reason:handoffFailed", failure);
        }
    }

    private PendingHandoff simulate(StandardMessage message, boolean retryAttempt, boolean postFilters) {
        if (message.type == StandardMessage.MSG_DLR) {
            throw new IllegalArgumentException("simulatorSubmission|error|reason:notAnSms");
        }
        final Settings current = settings;
        // One logical provider request; no SMPP PDU segmentation or network protocol is simulated.
        String providerId = "sim-" + UUID.randomUUID();
        String hashed = getHashedMessageID(providerId);
        message.extrid = providerId;
        message.field1 = hashed;
        if (providerMetrics != null) {
            providerMetrics.start(getDlrProviderName(), retryAttempt).complete(ProviderRequestMetrics.Result.ACCEPTED);
        }
        var accepted = new AtomicBoolean();
        var resolved = new AtomicBoolean();
        boolean dlr = current.generateDlrs() && message.dlrReturnMetadata != null;
        return new PendingHandoff(message.serial, () -> {
            CompletionStage<Void> acceptance = !dlr || accepted.get() ? CompletableFuture.completedStage(null) :
                    messageTracker.handoffProviderAccepted(hashed, message, providerId).thenRun(() -> accepted.set(true));
            return acceptance.thenCompose(ignored -> {
                if (!dlr || resolved.get()) {
                    return CompletableFuture.completedStage(null);
                }
                Outcome outcome = current.outcome();
                return messageTracker.createAndEnqueueDlrAsync(message.msgId, providerId, hashed,
                        message.from, message.to, "", outcome.state, outcome.error, null)
                        .thenRun(() -> resolved.set(true));
            }).thenAccept(ignored -> {
                if (postFilters) {
                    try {
                        onMessageSuccess(message);
                    } catch (IOException failure) {
                        throw new java.util.concurrent.CompletionException(failure);
                    }
                }
            });
        });
    }

    private record Settings(Outcome outcome, boolean generateDlrs) { }

    private enum Outcome {
        DELIVERED(StandardMessage.DLR_STAT_DELIVRD, StandardMessage.DLR_ERR_SUCCESS),
        FAILED(StandardMessage.DLR_STAT_FAILED, StandardMessage.DLR_ERR_SMS_FAILED),
        EXPIRED(StandardMessage.DLR_STAT_EXPIRED, StandardMessage.DLR_ERR_SMS_EXPIRED);

        private final int state;
        private final String error;

        Outcome(int state, int error) {
            this.state = state;
            this.error = Integer.toString(error);
        }
    }

    /** Retains ownership after a failed handoff; the dispatch loop explicitly retries without resubmission. */
    private final class PendingHandoff implements Submission {
        private final String gatewayId;
        private final Supplier<CompletionStage<Void>> operation;
        private final CompletableFuture<Void> completed = new CompletableFuture<>();
        private final CompletableFuture<Void> firstAttempt = new CompletableFuture<>();
        private boolean active;
        private boolean failureReported;

        private PendingHandoff(String gatewayId, Supplier<CompletionStage<Void>> operation) {
            this.gatewayId = gatewayId;
            this.operation = operation;
            retryPendingHandoffs();
        }

        @Override
        public synchronized void retryPendingHandoffs() {
            if (active || completed.isDone()) {
                return;
            }
            active = true;
            try {
                operation.get().whenComplete((ignored, failure) -> {
                    synchronized (this) {
                        active = false;
                        if (failure == null) {
                            completed.complete(null);
                            firstAttempt.complete(null);
                        } else {
                            firstAttempt.completeExceptionally(failure);
                            reportFailure(failure);
                        }
                    }
                });
            } catch (RuntimeException failure) {
                active = false;
                firstAttempt.completeExceptionally(failure);
                reportFailure(failure);
            }
        }

        private void reportFailure(Throwable failure) {
            if (!failureReported) {
                failureReported = true;
                logger.warn("simulatorHandoff|retry|gatewayMsgId:{}|reason:trackingFailure",
                        MessageTrace.value(gatewayId), failure);
            }
        }

        @Override
        public CompletionStage<Void> completion() {
            return completed.minimalCompletionStage();
        }
    }
}
