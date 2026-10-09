package gr.cytech.sendium.core.storage;

import java.util.Objects;

/**
 * An explicitly owned, single-use storage lifecycle. Construction does not start a stage.
 * Operations require READY; closing is idempotent and does not imply terminal completion.
 * Implementations must support concurrent operation calls while open.
 *
 * <p>Worker/filter mutations belong to execution state and are preserved through explicit transitions
 * and returns. They must not silently change an earlier-stage record through a shared mutable object.
 * Recorded payload and processing stage must stay consistent. Snapshot/mapping/ownership mechanisms
 * are implementation choices, not a requirement to copy on every operation. Custom message subtypes
 * and their fields must be preserved.
 */
public interface OutboundStage extends AutoCloseable {
    void open();

    Status status();

    @Override
    void close();

    enum Role {
        PENDING, ROUTER_QUEUE, ROUTED_WORK
    }

    enum State {
        NEW, READY, STOPPING, CLOSED, FAILED
    }

    /** Backend names are implementation identities, not the list of supported standalone profiles. */
    record Status(String backend, boolean durable, State state) {
        public Status {
            Objects.requireNonNull(backend, "backend");
            Objects.requireNonNull(state, "state");
            if (backend.isBlank()) {
                throw new IllegalArgumentException("Backend identity must not be blank");
            }
        }
    }
}
