package gr.cytech.sendium.core.worker;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
public class MessageState implements Serializable {
    private static final long serialVersionUID = 1L;

    private String gatewayMsgId;
    private String accountId;
    private String systemId;
    private String sourceAddr;
    private String destAddr;
    private String providerName;
    private String providerMessageId;
    private String forwardDlrUrl;
    private List<String> reassembledParts;
    private MessageStatus status;
    private Integer dlrState;
    private String errorCode;
    private DeliveryChannel deliveryChannel = DeliveryChannel.NONE;
    private DeliveryStatus deliveryStatus = DeliveryStatus.WAITING_PROVIDER;
    private int deliveryAttemptCount;
    private Long lastAttemptAt;
    private Long nextAttemptAt;
    private String lastDeliveryResult;
    private Long resolvedAt;
    private long timestamp;

    public MessageState() {
    }

    public MessageState(String gatewayMsgId, String systemId, String sourceAddr, String destAddr, String forwardDlrUrl) {
        this(gatewayMsgId, systemId, systemId, sourceAddr, destAddr, forwardDlrUrl);
    }

    public MessageState(String gatewayMsgId, String accountId, String systemId, String sourceAddr, String destAddr, String forwardDlrUrl) {
        this.gatewayMsgId = gatewayMsgId;
        this.accountId = accountId;
        this.systemId = systemId;
        this.sourceAddr = sourceAddr;
        this.destAddr = destAddr;
        this.providerName = null;
        this.providerMessageId = null;
        this.status = MessageStatus.ACCEPTED;
        this.deliveryAttemptCount = 0;
        this.timestamp = System.currentTimeMillis();
        this.forwardDlrUrl = forwardDlrUrl;
    }

    public String getGatewayMsgId() {
        return gatewayMsgId;
    }

    public String getAccountId() {
        return accountId == null || accountId.isBlank() ? systemId : accountId;
    }

    public String getSystemId() {
        return systemId;
    }

    public String getSourceAddr() {
        return sourceAddr;
    }

    public String getDestAddr() {
        return destAddr;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getForwardDlrUrl() {
        return forwardDlrUrl;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public Integer getDlrState() {
        return dlrState;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public DeliveryChannel getDeliveryChannel() {
        return deliveryChannel;
    }

    public DeliveryStatus getDeliveryStatus() {
        return deliveryStatus;
    }

    public int getDeliveryAttemptCount() {
        return deliveryAttemptCount;
    }

    public Long getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Long getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastDeliveryResult() {
        return lastDeliveryResult;
    }

    public Long getResolvedAt() {
        return resolvedAt;
    }

    public List<String> getReassembledParts() {
        return reassembledParts == null ? null : new ArrayList<>(reassembledParts);
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setProviderMessageId(String providerMessageId) {
        this.providerMessageId = providerMessageId;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public void setStatus(MessageStatus status) {
        this.status = status;
    }

    public void setDlrState(Integer dlrState) {
        this.dlrState = dlrState;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public void setDeliveryChannel(DeliveryChannel deliveryChannel) {
        this.deliveryChannel = deliveryChannel;
    }

    public void setDeliveryStatus(DeliveryStatus deliveryStatus) {
        this.deliveryStatus = deliveryStatus;
    }

    public void setDeliveryAttemptCount(int deliveryAttemptCount) {
        this.deliveryAttemptCount = deliveryAttemptCount;
    }

    public void setLastAttemptAt(Long lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public void setNextAttemptAt(Long nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public void setLastDeliveryResult(String lastDeliveryResult) {
        this.lastDeliveryResult = lastDeliveryResult;
    }

    public void setResolvedAt(Long resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public void setReassembledParts(List<String> reassembledParts) {
        this.reassembledParts = reassembledParts == null ? null : new ArrayList<>(reassembledParts);
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public enum MessageStatus {
        ACCEPTED,
        SENT,
        DELIVERED,
        FAILED
    }

    public enum DeliveryChannel {
        NONE,
        HTTP,
        SMPP
    }

    public enum DeliveryStatus {
        WAITING_PROVIDER,
        PENDING,
        FAILED
    }
}
