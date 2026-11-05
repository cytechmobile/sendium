package gr.cytech.sendium.core.message;

import java.io.Serializable;

public record DlrReturnMetadata(
        DeliveryChannel channel,
        String accountId,
        String systemId,
        String sourceAddress,
        String destinationAddress,
        String forwardDlrUrl,
        int dlrMask,
        HttpCallbackFormat httpCallbackFormat
) implements Serializable {
    public DlrReturnMetadata {
        if (httpCallbackFormat == null) {
            httpCallbackFormat = HttpCallbackFormat.KANNEL_GET;
        }
    }

    /** Retains the existing metadata constructor and legacy HTTP callback behavior. */
    public DlrReturnMetadata(DeliveryChannel channel, String accountId, String systemId,
                             String sourceAddress, String destinationAddress, String forwardDlrUrl) {
        this(channel, accountId, systemId, sourceAddress, destinationAddress, forwardDlrUrl, 3, HttpCallbackFormat.KANNEL_GET);
    }

    public enum HttpCallbackFormat {
        KANNEL_GET,
        JSON_POST
    }

    public enum DeliveryChannel {
        HTTP,
        SMPP
    }

    public DlrReturnMetadata(DeliveryChannel channel, String accountId, String systemId,
                             String sourceAddress, String destinationAddress, String forwardDlrUrl, int dlrMask) {
        this(channel, accountId, systemId, sourceAddress, destinationAddress, forwardDlrUrl, dlrMask, HttpCallbackFormat.KANNEL_GET);
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

    public static DlrReturnMetadata httpJson(String accountId, String sourceAddress,
                                            String destinationAddress, String forwardDlrUrl) {
        return new DlrReturnMetadata(DeliveryChannel.HTTP, accountId, accountId,
                sourceAddress, destinationAddress, forwardDlrUrl, 3, HttpCallbackFormat.JSON_POST);
    }
}
