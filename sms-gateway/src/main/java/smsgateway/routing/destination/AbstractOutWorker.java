package smsgateway.routing.destination;

import com.google.common.util.concurrent.RateLimiter;
import jakarta.inject.Inject;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import smsgateway.dto.IncomingSms;
import smsgateway.services.DlrMappingService;
import smsgateway.smpp.VendorConf;

public abstract class AbstractOutWorker implements MessageDestination {

    protected final VendorConf vendorConf;
    protected BlockingQueue<IncomingSms> messageQueue;
    protected RateLimiter rateLimiter;

    protected final DlrMappingService dlrMappingService;

    @Inject
    public AbstractOutWorker(
            VendorConf vendorConf, DlrMappingService dlrMappingService) { // New parameter
        this.vendorConf = vendorConf;
        this.messageQueue = new LinkedBlockingQueue<>();
        this.dlrMappingService = dlrMappingService;
        var tps = vendorConf.getTransactionsPerSecond();
        // if http worker
        if (tps > 0) {
            this.rateLimiter = RateLimiter.create(tps);
        } else {
            this.rateLimiter = RateLimiter.create(Double.MAX_VALUE);
        }
    }

    public void process(IncomingSms message, String ruleName, String destinationId) {}

    public void stopWorker() {}

    public VendorConf getVendorConf() {
        return vendorConf;
    }

    public BlockingQueue<IncomingSms> getMessageQueue() {
        return messageQueue;
    }
}
