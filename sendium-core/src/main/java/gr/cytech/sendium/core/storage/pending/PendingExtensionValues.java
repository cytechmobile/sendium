package gr.cytech.sendium.core.storage.pending;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A closed value vocabulary; type tags are schema names, never Java class names. */
final class PendingExtensionValues {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final int MAX_DEPTH = 64;

    private PendingExtensionValues() {
    }

    static JsonNode encode(Object value) {
        return encode(value, new IdentityHashMap<>(), 0);
    }

    private static JsonNode encode(Object value, IdentityHashMap<Object, Boolean> path, int depth) {
        if (depth > MAX_DEPTH) {
            throw PendingRecordFields.unsupported();
        }
        if (value == null) {
            return NODES.nullNode();
        }
        ObjectNode result = NODES.objectNode();
        switch (value) {
            case String text -> scalar(result, "string", NODES.textNode(text));
            case Boolean flag -> scalar(result, "boolean", NODES.booleanNode(flag));
            case Byte number -> scalar(result, "int8", NODES.numberNode(number));
            case Short number -> scalar(result, "int16", NODES.numberNode(number));
            case Integer number -> scalar(result, "int32", NODES.numberNode(number));
            case Long number -> scalar(result, "int64", NODES.numberNode(number));
            case Float number -> {
                if (!Float.isFinite(number)) {
                    throw PendingRecordFields.unsupported();
                }
                scalar(result, "float32", NODES.numberNode(number));
            }
            case Double number -> {
                if (!Double.isFinite(number)) {
                    throw PendingRecordFields.unsupported();
                }
                scalar(result, "float64", NODES.numberNode(number));
            }
            case byte[] bytes -> scalar(result, "bytes", NODES.binaryNode(bytes.clone()));
            case List<?> list -> {
                enter(path, value);
                try {
                    var items = NODES.arrayNode();
                    for (Object item : list) {
                        items.add(encode(item, path, depth + 1));
                    }
                    scalar(result, "list", items);
                } finally {
                    path.remove(value);
                }
            }
            case Map<?, ?> map -> {
                enter(path, value);
                try {
                    var sorted = new TreeMap<String, Object>();
                    for (var entry : map.entrySet()) {
                        if (!(entry.getKey() instanceof String key)) {
                            throw PendingRecordFields.unsupported();
                        }
                        sorted.put(key, entry.getValue());
                    }
                    var entries = NODES.objectNode();
                    sorted.forEach((key, item) -> entries.set(key, encode(item, path, depth + 1)));
                    scalar(result, "map", entries);
                } finally {
                    path.remove(value);
                }
            }
            default -> throw PendingRecordFields.unsupported();
        }
        return result;
    }

    private static void scalar(ObjectNode result, String type, JsonNode value) {
        result.put("type", type);
        result.set("value", value);
    }

    private static void enter(IdentityHashMap<Object, Boolean> path, Object value) {
        if (path.put(value, Boolean.TRUE) != null) {
            throw PendingRecordFields.unsupported();
        }
    }

    static Object decode(JsonNode node) {
        return decode(node, 0);
    }

    private static Object decode(JsonNode node, int depth) {
        if (depth > MAX_DEPTH) {
            throw PendingRecordFields.corrupt();
        }
        if (node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw PendingRecordFields.corrupt();
        }
        String type = PendingRecordFields.nonNullText(PendingRecordFields.required(node, "type"));
        JsonNode value = PendingRecordFields.required(node, "value");
        return switch (type) {
            case "string" -> PendingRecordFields.nonNullText(value);
            case "boolean" -> PendingRecordFields.bool(value);
            case "int8" -> (byte) PendingRecordFields.integer(value, Byte.MIN_VALUE, Byte.MAX_VALUE);
            case "int16" -> (short) PendingRecordFields.integer(value, Short.MIN_VALUE, Short.MAX_VALUE);
            case "int32" -> PendingRecordFields.integer(value, Integer.MIN_VALUE, Integer.MAX_VALUE);
            case "int64" -> PendingRecordFields.integer(value);
            case "float32" -> {
                double number = PendingRecordFields.decimal(value);
                float narrowed = (float) number;
                if (!Float.isFinite(narrowed) || (double) narrowed != number) {
                    throw PendingRecordFields.corrupt();
                }
                yield narrowed;
            }
            case "float64" -> PendingRecordFields.decimal(value);
            case "bytes" -> bytes(value);
            case "list" -> {
                if (!value.isArray()) {
                    throw PendingRecordFields.corrupt();
                }
                var items = new ArrayList<>();
                for (JsonNode item : value) {
                    items.add(decode(item, depth + 1));
                }
                yield items;
            }
            case "map" -> {
                if (!value.isObject()) {
                    throw PendingRecordFields.corrupt();
                }
                var entries = new TreeMap<String, Object>();
                value.properties().forEach(entry -> entries.put(entry.getKey(), decode(entry.getValue(), depth + 1)));
                yield entries;
            }
            default -> throw PendingRecordFields.corrupt();
        };
    }

    private static byte[] bytes(JsonNode node) {
        if (!node.isBinary()) {
            throw PendingRecordFields.corrupt();
        }
        try {
            return node.binaryValue().clone();
        } catch (IOException failure) {
            throw PendingRecordFields.corrupt();
        }
    }
}
