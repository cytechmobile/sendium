package gr.cytech.sendium.app.storage;

import gr.cytech.sendium.core.message.StandardMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Snapshot policy for the standalone protocol payload; embedded applications supply their own mapper. */
final class StandaloneMessageSnapshot {
    private StandaloneMessageSnapshot() {
    }

    static StandardMessage copy(StandardMessage message) {
        if (message.getClass() != StandardMessage.class) {
            throw new IllegalArgumentException("Custom messages require an application-owned outbound snapshot policy");
        }
        try {
            var copy = (StandardMessage) message.clone();
            copy.attrs = message.attrs == null ? null : copyMap(message.attrs);
            copy.tlvs = message.tlvs == null ? null : new HashMap<>(message.tlvs);
            copy.reassembledParts = message.reassembledParts == null ? null : new ArrayList<>(message.reassembledParts);
            copy.field1 = value(message.field1);
            copy.field2 = value(message.field2);
            copy.field3 = value(message.field3);
            copy.field4 = value(message.field4);
            copy.field5 = value(message.field5);
            copy.field6 = value(message.field6);
            copy.field7 = value(message.field7);
            copy.field8 = value(message.field8);
            copy.field9 = value(message.field9);
            copy.field10 = value(message.field10);
            return copy;
        } catch (CloneNotSupportedException failure) {
            throw new IllegalStateException("Cannot snapshot standalone message", failure);
        }
    }

    private static Map<String, Object> copyMap(Map<String, Object> original) {
        var copy = new HashMap<String, Object>();
        original.forEach((key, item) -> copy.put(key, value(item)));
        return copy;
    }

    private static Object value(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Character ||
                value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long ||
                value instanceof Float || value instanceof Double || value instanceof Enum<?>) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value instanceof List<?> list) {
            var copy = new ArrayList<>();
            list.forEach(item -> copy.add(value(item)));
            return copy;
        }
        if (value instanceof Map<?, ?> map) {
            var copy = new HashMap<>();
            map.forEach((key, item) -> copy.put(value(key), value(item)));
            return copy;
        }
        throw new IllegalArgumentException("Unsupported mutable standalone message field: " + value.getClass().getName());
    }
}
