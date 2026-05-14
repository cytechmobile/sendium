package gr.cytech.sendium.core.smpp.server;

public interface SmppSessionContext {
    String getAccountId();

    String getSystemId();

    int getMaxConnections();

    double getMaxRate();

    int getWindowSize();

    long getWindowMonitorInterval();

    long getRequestExpiryTimeout();

    long getWriteTimeout();

    String getProduct();
}
