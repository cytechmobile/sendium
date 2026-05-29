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
    private String operatorMsgId;
    private String forwardDlrUrl;
    private List<String> reassembledParts;
    private MessageStatus status;
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
        this.operatorMsgId = null;
        this.status = MessageStatus.ACCEPTED;
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

    public String getOperatorMsgId() {
        return operatorMsgId;
    }

    public String getForwardDlrUrl() {
        return forwardDlrUrl;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public List<String> getReassembledParts() {
        return reassembledParts == null ? null : new ArrayList<>(reassembledParts);
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setOperatorMsgId(String operatorMsgId) {
        this.operatorMsgId = operatorMsgId;
    }

    public void setStatus(MessageStatus status) {
        this.status = status;
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
}
