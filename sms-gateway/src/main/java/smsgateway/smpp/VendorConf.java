package smsgateway.smpp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class VendorConf {

    private String id;
    private boolean enabled;
    private String host;
    private int port;
    private String systemId;
    private String password;
    private int reconnectIntervalSeconds;
    private int enquireLinkIntervalSeconds;
    private double transactionsPerSecond; // New field

    private String type;
    private String httpApiKey;
    private String httpApiUrl;

    public VendorConf() {
        this.reconnectIntervalSeconds = 30;
        this.enquireLinkIntervalSeconds = 60;
        this.transactionsPerSecond = 10.0; // Default TPS
    }

    // smpp
    public VendorConf(
            String id,
            boolean enabled,
            String host,
            int port,
            String systemId,
            String password,
            int reconnectIntervalSeconds,
            int enquireLinkIntervalSeconds,
            double transactionsPerSecond,
            String type) { // New parameter
        this.id = id;
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.systemId = systemId;
        this.password = password;
        this.reconnectIntervalSeconds = reconnectIntervalSeconds;
        this.enquireLinkIntervalSeconds = enquireLinkIntervalSeconds;
        this.transactionsPerSecond = transactionsPerSecond; // Assign new parameter
        this.type = type;
    }

    // http
    public VendorConf(
            String id,
            boolean enabled,
            String type,
            String httpApiKey,
            String httpApiUrl) { // New parameter
        this.id = id;
        this.enabled = enabled;
        this.transactionsPerSecond = transactionsPerSecond; // Assign new parameter
        this.type = type;
        this.httpApiKey = httpApiKey;
        this.httpApiUrl = httpApiUrl;
    }

    public VendorConf(
            String id,
            boolean enabled,
            String host,
            int port,
            String systemId,
            String password,
            int reconnectIntervalSeconds,
            int enquireLinkIntervalSeconds,
            double transactionsPerSecond,
            String type,
            String httpApiKey,
            String httpApiUrl) { // New parameter
        this.id = id;
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.systemId = systemId;
        this.password = password;
        this.reconnectIntervalSeconds = reconnectIntervalSeconds;
        this.enquireLinkIntervalSeconds = enquireLinkIntervalSeconds;
        this.transactionsPerSecond = transactionsPerSecond; // Assign new parameter
        this.type = type;
        this.httpApiKey = httpApiKey;
        this.httpApiUrl = httpApiUrl;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getSystemId() {
        return systemId;
    }

    public void setSystemId(String systemId) {
        this.systemId = systemId;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getReconnectIntervalSeconds() {
        return reconnectIntervalSeconds;
    }

    public void setReconnectIntervalSeconds(int reconnectIntervalSeconds) {
        this.reconnectIntervalSeconds = reconnectIntervalSeconds;
    }

    public int getEnquireLinkIntervalSeconds() {
        return enquireLinkIntervalSeconds;
    }

    public void setEnquireLinkIntervalSeconds(int enquireLinkIntervalSeconds) {
        this.enquireLinkIntervalSeconds = enquireLinkIntervalSeconds;
    }

    public double getTransactionsPerSecond() { // New getter
        return transactionsPerSecond;
    }

    public String getHttpApiKey() {
        return httpApiKey;
    }

    public String getHttpApiUrl() {
        return httpApiUrl;
    }

    public String getType() {
        return type;
    }

    public void setTransactionsPerSecond(double transactionsPerSecond) { // New setter
        this.transactionsPerSecond = transactionsPerSecond;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        VendorConf that = (VendorConf) o;

        if (enabled != that.enabled) return false;
        if (port != that.port) return false;
        if (reconnectIntervalSeconds != that.reconnectIntervalSeconds) return false;
        if (enquireLinkIntervalSeconds != that.enquireLinkIntervalSeconds) return false;
        if (Double.compare(that.transactionsPerSecond, transactionsPerSecond) != 0)
            return false; // Compare new field
        if (!id.equals(that.id)) return false;
        if (!host.equals(that.host)) return false;
        if (!systemId.equals(that.systemId)) return false;
        return password.equals(that.password);
    }

    @Override
    public int hashCode() {
        int result;
        long temp;
        result = id.hashCode();
        result = 31 * result + (enabled ? 1 : 0);
        temp = Double.doubleToLongBits(transactionsPerSecond); // Hash new field
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        return result;
    }
}
