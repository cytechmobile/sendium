package gr.cytech.sendium.core.message;

import java.io.Serializable;

public record DlrReturnMetadata(
        DeliveryChannel channel,
        String accountId,
        String systemId,
        String sourceAddress,
        String destinationAddress,
        String forwardDlrUrl,
        int dlrMask
) implements Serializable {
    public enum DeliveryChannel {
        HTTP,
        SMPP
    }

    public DlrReturnMetadata(DeliveryChannel channel, String accountId, String systemId,
                             String sourceAddress, String destinationAddress, String forwardDlrUrl) {
        this(channel, accountId, systemId, sourceAddress, destinationAddress, forwardDlrUrl, 3);
    }

    public static DlrReturnMetadata http(String accountId, String sourceAddress,
                                         String destinationAddress, String forwardDlrUrl, int dlrMask) {
        return new DlrReturnMetadata(DeliveryChannel.HTTP, accountId, accountId,
                sourceAddress, destinationAddress, forwardDlrUrl, dlrMask);
    }

    public static DlrReturnMetadata http(String accountId, String sourceAddress,
                                         String destinationAddress, String forwardDlrUrl) {
        return new DlrReturnMetadata(DeliveryChannel.HTTP, accountId, accountId,
                sourceAddress, destinationAddress, forwardDlrUrl);
    }

    public static DlrReturnMetadata smpp(String accountId, String systemId,
                                         String sourceAddress, String destinationAddress) {
        return new DlrReturnMetadata(DeliveryChannel.SMPP, accountId, systemId,
                sourceAddress, destinationAddress, null);
    }
}
