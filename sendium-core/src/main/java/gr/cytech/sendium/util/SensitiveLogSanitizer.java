package gr.cytech.sendium.util;

import java.util.Locale;

public final class SensitiveLogSanitizer {
    public static final String REDACTED = "*****";

    private SensitiveLogSanitizer() {
    }

    public static String maskValue(String key, String value) {
        if (value == null) {
            return "null";
        }
        return isSensitiveKey(key) ? REDACTED : value;
    }

    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }

        String lowerKey = key.toLowerCase(Locale.ROOT);
        return lowerKey.contains("password") ||
                lowerKey.contains("secret") ||
                lowerKey.contains("token") ||
                lowerKey.contains("apikey") ||
                lowerKey.contains("api_key") ||
                lowerKey.contains("api-key") ||
                lowerKey.contains("api.key") ||
                lowerKey.contains("privatekey") ||
                lowerKey.contains("private_key") ||
                lowerKey.contains("private-key") ||
                lowerKey.contains("private.key");
    }
}
