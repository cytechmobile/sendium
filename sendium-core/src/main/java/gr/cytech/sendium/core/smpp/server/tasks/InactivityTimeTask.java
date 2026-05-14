package gr.cytech.sendium.core.smpp.server.tasks;

import gr.cytech.sendium.core.smpp.server.SmppServerWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InactivityTimeTask implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(InactivityTimeTask.class);
    private final SmppServerWorker worker;

    public InactivityTimeTask(SmppServerWorker worker) {
        this.worker = worker;
    }

    public void run() {
        logger.trace("Checking inactivity time.");
        worker.checkInactivityTime();
    }
}
