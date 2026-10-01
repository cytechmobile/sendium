package gr.cytech.sendium.routing;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.Routed;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.smpp.client.CoordinatedSmppSubmission;
import gr.cytech.sendium.core.smpp.client.SmppClientWorker;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

/** Explicitly assembled routing and provider-completion boundary for the single-destination lifecycle. */
public final class StandardOutboundDispatch {
    private final OutboundCoordinator<StandardMessage> coordinator;
    private final StandardRoutingManager routing;

    public StandardOutboundDispatch(OutboundCoordinator<StandardMessage> coordinator, StandardRoutingManager routing) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.routing = Objects.requireNonNull(routing, "routing");
    }

    /**
     * Routes one owned selection. A miss or lookup failure returns it to routing. If recording the
     * destination fails, the caller retains the selection handle to retry the same routing intent.
     */
    public Optional<Routed<StandardMessage>> routeSelected(Selected<StandardMessage> selected) throws IOException {
        Objects.requireNonNull(selected, "selected");
        RoutingLookupResult result;
        try {
            result = routing.lookupForLifecycle(selected.message());
        } catch (IOException | RuntimeException failure) {
            coordinator.returnToRouter(selected);
            throw failure;
        }
        if (result.getDestinations().isEmpty()) {
            coordinator.returnToRouter(selected);
            return Optional.empty();
        }
        if (result.getDestinations().size() != 1) {
            coordinator.returnToRouter(selected);
            throw new OutboundStorageException(OutboundStage.Role.ROUTER_QUEUE,
                    OutboundStorageException.Reason.UNSUPPORTED, "Lifecycle routing requires one destination");
        }
        String destination = result.getDestinations().getFirst().getFullName();
        return Optional.of(coordinator.route(selected, new Destination<>(destination, selected.message())));
    }

    /** Takes recorded work for provider processing; taking or enqueuing it is not terminal completion. */
    public Optional<Routed<StandardMessage>> takeForProvider(String destination, Duration timeout) throws InterruptedException {
        return coordinator.takeForDestination(destination, timeout);
    }

    /** Runs an explicitly taken SMPP work item and carries its ownership through replacement attempts. */
    public ProviderExecution submitToProvider(Routed<StandardMessage> work, SmppClientWorker<StandardMessage> worker)
            throws Exception {
        if (!work.destination().equals(worker.getFullName())) {
            throw new IllegalArgumentException("Provider does not match the recorded destination");
        }
        var execution = new ProviderExecution();
        execution.retryCompletion = () -> coordinator.complete(work.id(), execution.attempts);
        CompletionStage<Void> attempts = submitAttempt(work.message(), worker, execution);
        execution.attempts = attempts;
        execution.completion = coordinator.complete(work.id(), attempts);
        return execution;
    }

    private CompletionStage<Void> submitAttempt(StandardMessage message, SmppClientWorker<StandardMessage> worker,
                                                 ProviderExecution execution) throws Exception {
        var submission = worker.submitCoordinated(message, (retryMessage, policy) ->
            CompletableFuture.supplyAsync(() -> {
                try {
                    SmppClientWorker<StandardMessage> destination = worker;
                    if (policy == SmppClientWorker.NackHandlePolicy.RETRY_ROUTER) {
                        RoutingLookupResult result = routing.lookupForLifecycle(retryMessage);
                        if (result.getDestinations().size() != 1 ||
                                !(result.getDestinations().getFirst() instanceof SmppClientWorker<?>)) {
                            throw new OutboundStorageException(OutboundStage.Role.ROUTER_QUEUE,
                                    OutboundStorageException.Reason.UNAVAILABLE, "No single SMPP destination for retry");
                        }
                        destination = (SmppClientWorker<StandardMessage>) result.getDestinations().getFirst();
                    }
                    return submitAttempt(retryMessage, destination, execution);
                } catch (Exception failure) {
                    return CompletableFuture.<Void>failedStage(failure);
                }
            }).thenCompose(stage -> stage));
        execution.submissions.add(submission);
        return submission.completion();
    }

    /**
     * Called only once the provider has produced an outcome for every part. Each stage represents
     * the required handoff for that part (an already successful stage if none is required). Pending
     * handoffs retain ownership; failure retains the work for a completion-only retry with new stages.
     */
    public CompletionStage<Void> finishProviderParts(Routed<StandardMessage> work,
                                                      List<? extends CompletionStage<Void>> requiredHandoffs) {
        Objects.requireNonNull(work, "work");
        List<? extends CompletionStage<Void>> handoffs = List.copyOf(requiredHandoffs);
        if (handoffs.isEmpty()) {
            throw new IllegalArgumentException("A provider outcome must include at least one part");
        }
        CompletableFuture<?>[] parts = handoffs.stream().map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new);
        return coordinator.complete(work.id(), CompletableFuture.allOf(parts));
    }

    /** Runtime attempt handles remain associated with the parent's recorded work, never new admissions. */
    public static final class ProviderExecution {
        private final List<CoordinatedSmppSubmission<StandardMessage>> submissions = new CopyOnWriteArrayList<>();
        private CompletionStage<Void> completion;
        private CompletionStage<Void> attempts;
        private java.util.function.Supplier<CompletionStage<Void>> retryCompletion;

        public CompletionStage<Void> completion() {
            return completion;
        }

        public void retryPendingHandoffs() {
            List.copyOf(submissions).forEach(CoordinatedSmppSubmission::retryPendingHandoffs);
        }

        /** Retries store cleanup after successful handoffs without sending the message again. */
        public CompletionStage<Void> retryTerminalCleanup() {
            completion = retryCompletion.get();
            return completion;
        }
    }
}
