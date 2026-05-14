package gr.cytech.sendium.core.smpp.server;

import com.google.common.base.Strings;
import gr.cytech.sendium.auth.CredentialFileWatcher;

public class BasicSmppSessionContext implements SmppSessionContext {

    private final String accountId;
    private final String systemId;
    private final int maxConnections;
    private final double maxRate;
    private final int windowSize;
    private final long windowMonitorInterval;
    private final long requestExpiryTimeout;
    private final long writeTimeout;
    private final String product;

    public BasicSmppSessionContext(SmppServerWorker worker, CredentialFileWatcher.Credential credential) {
        // For the basic file-based auth, the systemId serves as the accountId
        this.systemId = credential.systemId();
        this.accountId = Strings.isNullOrEmpty(credential.accountId()) ? credential.systemId() : credential.accountId();

        // Assuming your basic SmppServerWorker has getters for global defaults.
        // If you decide to add these fields to the YAML, you would change this to something like:
        // this.maxConnections = credential.maxConnections() != null ? credential.maxConnections() : worker.getDefaultMaxConnections();
        this.maxConnections = worker.getDefaultMaxConnectionPerUser();
        this.maxRate = worker.getMaxRate(credential.accountId());
        this.windowSize = worker.getMaxPending();
        this.windowMonitorInterval = worker.getWindowMonitorInterval();
        this.requestExpiryTimeout = worker.getResponseTimeout();
        this.writeTimeout = worker.getWriteTimeout();

        // Product can be mapped from the credential if you add it to the YAML, otherwise default to empty
        this.product = credential.product() != null ? credential.product() : "";
    }

    @Override
    public String getAccountId() {
        return accountId;
    }

    @Override
    public String getSystemId() {
        return systemId;
    }

    @Override
    public int getMaxConnections() {
        return maxConnections;
    }

    @Override
    public double getMaxRate() {
        return maxRate;
    }

    @Override
    public int getWindowSize() {
        return windowSize;
    }

    @Override
    public long getWindowMonitorInterval() {
        return windowMonitorInterval;
    }

    @Override
    public long getRequestExpiryTimeout() {
        return requestExpiryTimeout;
    }

    @Override
    public long getWriteTimeout() {
        return writeTimeout;
    }

    @Override
    public String getProduct() {
        return product;
    }
}