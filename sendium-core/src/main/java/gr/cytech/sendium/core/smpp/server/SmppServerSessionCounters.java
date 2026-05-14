package gr.cytech.sendium.core.smpp.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SmppServerSessionCounters {
    private int count;
    private long period;

    private Statistic inRequest;
    private Statistic inResponse;
    private Statistic outRequest;
    private Statistic outResponse;

    private Logger logger;

    public SmppServerSessionCounters() {
        this(0, 0);
    }

    public SmppServerSessionCounters(int count, int period) {
        logger = LoggerFactory.getLogger(this.getClass());

        inRequest = new Statistic("InReq", "pdus", count, period);
        inResponse = new Statistic("InRsp", "pdus", count, period);
        outRequest = new Statistic("OutReq", "pdus", count, period);
        outResponse = new Statistic("OutRsp", "pdus", count, period);

        inRequest.init();
        inResponse.init();
        outRequest.init();
        outResponse.init();
    }

    public void setCount(int count) {
        this.count = count;
        inRequest.setCount(count);
        inResponse.setCount(count);
        outRequest.setCount(count);
        outResponse.setCount(count);
    }

    public void setPeriod(long period) {
        this.period = period;
        inRequest.setPeriod(period);
        inResponse.setPeriod(period);
        outRequest.setPeriod(period);
        outResponse.setPeriod(period);
    }

    public void checkGetInRequestStats() {
        String stat = inRequest.checkGetStats();

        //if we need to print the statistics based on the configuration
        if (stat != null && (count > 0 || period > 0)) {
            logger.info(stat);
        }
    }

    public void checkGetInResponsesStats() {
        String stat = inResponse.checkGetStats();

        //if we need to print the statistics based on the configuration
        if (stat != null && (count > 0 || period > 0)) {
            logger.info(stat);
        }
    }

    public void checkGetOutRequestStats() {
        String stat = outRequest.checkGetStats();

        //if we need to print the statistics based on the configuration
        if (stat != null && (count > 0 || period > 0)) {
            logger.info(stat);
        }
    }

    public void checkGetOutResponsesStats() {
        String stat = outResponse.checkGetStats();

        //if we need to print the statistics based on the configuration
        if (stat != null && (count > 0 || period > 0)) {
            logger.info(stat);
        }
    }

    public int getInRequestStats() {
        return inRequest.get();
    }

    public int getInResponsesStats() {
        return inResponse.get();
    }

    public int getOutRequestStats() {
        return outRequest.get();
    }

    public int getOutResponsesStats() {
        return outResponse.get();
    }
}
