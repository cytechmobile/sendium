package smsgateway.dto;

import java.time.Instant;

public class DlrForwardingPayload {

    private String smscid;
    private String status;
    private String errorCode;
    private Instant forwardDate;
    private String forwardUrl;
    private String rawDlr;
    private String forwardingId;
    private Instant receivedAt;
    private Instant sentAt;
    private Instant processedAt;
    private Long originatingSessionId;
    private String body;
    private String fromAddress;
    private String toAddress;
    private IncomingSms message;

    public DlrForwardingPayload() {}

    public DlrForwardingPayload(String smscid, String status, String errorCode, String rawDlr) {
        this.smscid = smscid;
        this.status = status;
        this.errorCode = errorCode;
        this.rawDlr = rawDlr;
    }

    public String getForwardUrl() {
        return forwardUrl;
    }

    public void setForwardUrl(String forwardUrl) {
        this.forwardUrl = forwardUrl;
    }

    public String getSmscid() {
        return smscid;
    }

    public void setSmscid(String smscid) {
        this.smscid = smscid;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public Long getOriginatingSessionId() {
        return originatingSessionId;
    }

    public void setOriginatingSessionId(Long originatingSessionId) {
        this.originatingSessionId = originatingSessionId;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public Instant getForwardDate() {
        return forwardDate;
    }

    public void setForwardDate(Instant forwardDate) {
        this.forwardDate = forwardDate;
    }

    public String getRawDlr() {
        return rawDlr;
    }

    public void setRawDlr(String rawDlr) {
        this.rawDlr = rawDlr;
    }

    public String getForwardingId() {
        return forwardingId;
    }

    public void setForwardingId(String forwardingId) {
        this.forwardingId = forwardingId;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public void setFromAddress(String fromAddress) {
        this.fromAddress = fromAddress;
    }

    public String getToAddress() {
        return toAddress;
    }

    public void setToAddress(String toAddress) {
        this.toAddress = toAddress;
    }

    @Override
    public String toString() {
        return "DlrForwardingPayload{"
                + "smscid='"
                + smscid
                + '\''
                + ", status='"
                + status
                + '\''
                + ", errorCode='"
                + errorCode
                + '\''
                + ", forwardDate='"
                + forwardDate
                + '\''
                + ", rawDlr='"
                + rawDlr
                + '\''
                + ", forwardingId='"
                + forwardingId
                + '\''
                + ", originatingSessionId='"
                + originatingSessionId
                + '\''
                + ", receivedAt="
                + receivedAt
                + ", sentAt="
                + sentAt
                + ", processedAt="
                + processedAt
                + '}';
    }
}
