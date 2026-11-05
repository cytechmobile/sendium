package gr.cytech.sendium.core.http;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.fasterxml.jackson.databind.JsonNode;
import gr.cytech.sendium.core.message.StandardMessage;

import java.net.URI;
import java.util.Set;

/** Validated native fields, without Kannel query decoding or caller-supplied ownership/routing. */
public record NativeMessageRequest(String from, String to, String text, String encoding, int priority, String callbackUrl) {
    private static final Set<String> FIELDS = Set.of("from", "to", "text", "encoding", "priority", "callbackUrl");

    public static NativeMessageRequest parse(JsonNode json) {
        if (json == null || !json.isObject()) {
            throw new IllegalArgumentException("Request must be a JSON object");
        }
        json.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) {
                throw new IllegalArgumentException("Request contains an unsupported field");
            }
        });
        String from = string(json, "from", true);
        if (from.isBlank() || from.length() > 21 || from.chars().anyMatch(c -> c < 32 || c > 126)) {
            throw new IllegalArgumentException("from must contain 1 to 21 printable ASCII characters");
        }
        String to = string(json, "to", true);
        if (!to.matches("\\+?[0-9]{1,20}")) {
            throw new IllegalArgumentException("to must contain 1 to 20 digits with an optional leading +");
        }
        String text = string(json, "text", true);
        if (text.isEmpty() || text.length() > 4096) {
            throw new IllegalArgumentException("text must contain 1 to 4096 characters");
        }
        String encoding = string(json, "encoding", false);
        if (encoding == null) {
            encoding = "gsm";
        }
        switch (encoding) {
            case "gsm" -> {
                if (!text.equals(CharsetUtil.decode(CharsetUtil.encode(text, CharsetUtil.CHARSET_GSM), CharsetUtil.CHARSET_GSM))) {
                    throw new IllegalArgumentException("text cannot be represented in GSM; use encoding ucs2");
                }
            }
            case "ucs2" -> {
                if (text.chars().anyMatch(c -> Character.isSurrogate((char) c))) {
                    throw new IllegalArgumentException("ucs2 does not support surrogate characters or emoji");
                }
            }
            default -> throw new IllegalArgumentException("encoding must be gsm or ucs2");
        }
        int priority = StandardMessage.NORMAL_PRIORITY;
        if (json.has("priority")) {
            var node = json.get("priority");
            if (!node.isIntegralNumber() || !node.canConvertToInt() ||
                    node.intValue() < StandardMessage.LOW_PRIORITY || node.intValue() > StandardMessage.HIGH_PRIORITY) {
                throw new IllegalArgumentException("priority must be an integer from 1 to 3");
            }
            priority = node.intValue();
        }
        String callback = string(json, "callbackUrl", false);
        if (callback != null) {
            validateCallback(callback);
        }
        return new NativeMessageRequest(from, to, text, encoding, priority, callback);
    }

    private static String string(JsonNode json, String field, boolean required) {
        var node = json.get(field);
        if (node == null && !required) {
            return null;
        }
        if (node == null || !node.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return node.textValue();
    }

    private static void validateCallback(String callback) {
        if (callback.length() > 2048) {
            throw new IllegalArgumentException("callbackUrl must not exceed 2048 characters");
        }
        URI uri;
        try {
            uri = URI.create(callback);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("callbackUrl must be an absolute HTTP or HTTPS URL");
        }
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) ||
                uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null ||
                uri.getPort() > 65535 || uri.getPort() == 0) {
            throw new IllegalArgumentException("callbackUrl must be an absolute HTTP or HTTPS URL without user info or fragment");
        }
    }
}
