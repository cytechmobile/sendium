package gr.cytech.sendium.core.storage.pending;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.util.MessageUtil;

import java.util.Objects;

/** Logical matching identity; several independent group instances may share this key. */
public record MultipartKey(String accountId, String systemId, String sender, String recipient,
                           int referenceBits, int reference, int total, String normalizedUdh) {
    public MultipartKey {
        Objects.requireNonNull(normalizedUdh, "normalizedUdh");
        if ((referenceBits != 8 && referenceBits != 16) || reference < 0 ||
                reference > (referenceBits == 8 ? 255 : 65535) || total < 1 || total > 255) {
            throw new IllegalArgumentException("Invalid multipart identity");
        }
    }

    public static MultipartKey from(StandardMessage message) {
        var part = MessageUtil.concatenation(message).orElseThrow(() -> new IllegalArgumentException("Unsupported multipart header"));
        return new MultipartKey(message.owner_id, message.systemId, message.from, message.to,
                part.referenceBits(), part.reference(), part.total(), part.normalizedUdh());
    }
}
