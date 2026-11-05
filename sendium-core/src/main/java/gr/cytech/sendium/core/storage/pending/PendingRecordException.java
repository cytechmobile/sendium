package gr.cytech.sendium.core.storage.pending;

import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;

/** Codec-specific failure detail without expanding protocol-facing storage reason switches. */
public final class PendingRecordException extends OutboundStorageException {
    private final Kind kind;

    PendingRecordException(Kind kind) {
        super(OutboundStage.Role.PENDING, kind == Kind.UNSUPPORTED_VERSION ? Reason.UNSUPPORTED : Reason.UNAVAILABLE,
                kind == Kind.UNSUPPORTED_VERSION ? "Unsupported pending schema version (backend=codec)" : "Invalid pending record (backend=codec)");
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public enum Kind {
        CORRUPT_RECORD, UNSUPPORTED_VERSION
    }
}
