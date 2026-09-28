package gr.cytech.sendium.core.storage;

import java.util.Objects;

/** Operation failures must describe the boundary without including message content or credentials. */
public class OutboundStorageException extends RuntimeException {
    private final OutboundStage.Role stage;
    private final Reason reason;

    public OutboundStorageException(OutboundStage.Role stage, Reason reason, String message) {
        this(stage, reason, message, null);
    }

    public OutboundStorageException(OutboundStage.Role stage, Reason reason, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.stage = Objects.requireNonNull(stage, "stage");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public OutboundStage.Role stage() {
        return stage;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        UNAVAILABLE, CAPACITY_EXCEEDED, OWNERSHIP_CONFLICT, UNSUPPORTED, INVALID_TRANSITION
    }
}
