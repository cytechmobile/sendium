package gr.cytech.sendium.core.smpp.server.tasks;

import gr.cytech.sendium.core.smpp.server.SmppServerWorker;

public class PrintStatisticsTask implements Runnable {
    private final SmppServerWorker worker;

    public PrintStatisticsTask(SmppServerWorker worker) {
        this.worker = worker;
    }

    public void run() {
        worker.statsLogger.info("-------------------- Statistics Start ----------------------");
        worker.printServerTotalStatistics();
        worker.printSessionsTotalStatistics();
        worker.printServerConnectionsStatistics();
        worker.statsLogger.info("--------------------- Statistics End -----------------------");
    }
}
