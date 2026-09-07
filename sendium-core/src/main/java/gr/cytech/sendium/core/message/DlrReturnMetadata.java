package gr.cytech.sendium.core.message;

import java.io.Serializable;

public record DlrReturnMetadata(
        DeliveryChannel channel,
        String accountId,
        String systemId,
        String sourceAddress,
        String destinationAddress,
        String forwardDlrUrl
) implements Serializable {
    public enum DeliveryChannel {
        HTTP,
        SMPP
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
