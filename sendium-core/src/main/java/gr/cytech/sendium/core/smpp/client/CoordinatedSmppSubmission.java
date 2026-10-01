package gr.cytech.sendium.core.smpp.client;

import gr.cytech.sendium.core.message.StandardMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Tracks provider requests and their replacement attempts without admitting additional sources. */
public final class CoordinatedSmppSubmission<M extends StandardMessage> {
    private final List<Part<M>> parts = new ArrayList<>();
    private CompletionStage<Void> completion;

    Part<M> add(M message) {
        if (completion != null) {
            throw new IllegalStateException("Provider request registration is already sealed");
        }
        Part<M> part = new Part<>(message);
        parts.add(part);
        return part;
    }

    CompletionStage<Void> seal() {
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("Provider submission must contain a request");
        }
        completion = CompletableFuture.allOf(parts.stream().map(part -> part.completed)
                .toArray(CompletableFuture[]::new)).minimalCompletionStage();
        return completion;
    }

    /** Retries failed tracking or retry handoffs, never a successfully completed provider request. */
    public void retryPendingHandoffs() {
        parts.forEach(Part::retry);
    }

    public CompletionStage<Void> completion() {
        return Objects.requireNonNull(completion, "Submission has not been sealed");
    }

    public List<Throwable> handoffFailures() {
        return parts.stream().map(Part::failure).filter(Objects::nonNull).toList();
    }

    static final class Part<M extends StandardMessage> {
        final M message;
        private final CompletableFuture<Void> completed = new CompletableFuture<>();
        private Supplier<CompletionStage<Void>> handoff;
        private boolean responding;
        private boolean active;
        private boolean terminal;
        private Throwable failure;

        Part(M message) {
            this.message = Objects.requireNonNull(message, "message");
        }

        synchronized boolean claimResponse() {
            if (responding) {
                return false;
            }
            responding = true;
            return true;
        }

        CompletionStage<Void> completion() {
            return completed.minimalCompletionStage();
        }

        void finish(Supplier<CompletionStage<Void>> operation) {
            synchronized (this) {
                handoff = Objects.requireNonNull(operation, "operation");
            }
            retry();
        }

        void retry() {
            Supplier<CompletionStage<Void>> operation;
            synchronized (this) {
                if (handoff == null || active || terminal) {
                    return;
                }
                active = true;
                failure = null;
                operation = handoff;
            }
            try {
                Objects.requireNonNull(operation.get(), "handoff stage").whenComplete((ignored, error) -> {
                    synchronized (this) {
                        active = false;
                        failure = error;
                        terminal = error == null;
                    }
                    if (error == null) {
                        completed.complete(null);
                    }
                });
            } catch (RuntimeException error) {
                synchronized (this) {
                    active = false;
                    failure = error;
                }
            }
        }

        synchronized Throwable failure() {
            return failure;
        }
    }
}
