package gr.cytech.sendium.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class SecurityUtils {
    public static final String HASH_PREFIX_INTERNAL = "internal";

    private static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();

    public static String generateMD5(String key) {
        MessageDigest md5Digest;
        try {
            md5Digest = MessageDigest.getInstance("MD5");
            byte[] digested = md5Digest.digest(key.getBytes());
            return convertToHex(digested);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    public static String convertToHex(byte[] buf) {
        char[] chars = new char[2 * buf.length];
        for (int i = 0; i < buf.length; ++i) {
            chars[2 * i] = HEX_CHARS[(buf[i] & 0xF0) >>> 4];
            chars[2 * i + 1] = HEX_CHARS[buf[i] & 0x0F];
        }
        return new String(chars);
    }
}
