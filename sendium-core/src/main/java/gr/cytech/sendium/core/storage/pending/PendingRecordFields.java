package gr.cytech.sendium.core.storage.pending;

import com.fasterxml.jackson.databind.JsonNode;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;

/** Strict field readers shared by the explicit pending schema and its extension format. */
final class PendingRecordFields {
    private PendingRecordFields() {
    }

    static JsonNode required(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null) {
            throw corrupt();
        }
        return value;
    }

    static String text(JsonNode value) {
        if (value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw corrupt();
        }
        return value.textValue();
    }

    static String nonNullText(JsonNode value) {
        String result = text(value);
        if (result == null) {
            throw corrupt();
        }
        return result;
    }

    static long integer(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw corrupt();
        }
        return value.longValue();
    }

    static int integer(JsonNode value, int minimum, int maximum) {
        long result = integer(value);
        if (result < minimum || result > maximum) {
            throw corrupt();
        }
        return (int) result;
    }

    static boolean bool(JsonNode value) {
        if (!value.isBoolean()) {
            throw corrupt();
        }
        return value.booleanValue();
    }

    static double decimal(JsonNode value) {
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw corrupt();
        }
        return value.doubleValue();
    }

    static OutboundStorageException corrupt() {
        // Do not attach parser exceptions: their messages can contain SMS bodies or extension values.
        return new PendingRecordException(PendingRecordException.Kind.CORRUPT_RECORD);
    }

    static OutboundStorageException unsupported() {
        return new OutboundStorageException(OutboundStage.Role.PENDING, OutboundStorageException.Reason.UNSUPPORTED,
                "Unsupported pending record value (backend=codec)");
    }
}
