package smsgateway.dto;

public class IncomingSms {
    // No-argument constructor
    public IncomingSms() {}

    public IncomingSms(String from, String to, String text) {
        this.from = from;
        this.to = to;
        this.text = text;
    }

    private String from;
    private String to;
    private String text;
    private String timestamp;
    private String coding;
    private String internalId;
    private Long sessionId;
    private String gateway;
    private String forwardUrl;

    public Long getSessionId() {
        return sessionId;
    }

    public String getForwardUrl() {
        return forwardUrl;
    }

    public void setForwardUrl(String forwardUrl) {
        this.forwardUrl = forwardUrl;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getGateway() {
        return gateway;
    }

    public void setGateway(String gateway) {
        this.gateway = gateway;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getCoding() {
        return coding;
    }

    public void setCoding(String coding) {
        this.coding = coding;
    }

    public String getInternalId() {
        return internalId;
    }

    public void setInternalId(String internalId) {
        this.internalId = internalId;
    }
}
