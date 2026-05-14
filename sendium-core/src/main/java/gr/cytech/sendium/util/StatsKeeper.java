package gr.cytech.sendium.util;

import gr.cytech.sendium.conf.PropertyChangeEvent;
import gr.cytech.sendium.conf.SendiumConfigurationProvider;

public class StatsKeeper {
    public String[][] prms = {{"stats.count", "0"}, {"stats.period", "0"}};
    public String[] statsCount = prms[0];
    public String[] statsPeriod = prms[1];

    public int outSmsCnt;
    public int lastOutSmsCnt;
    public int nextOutSmsCnt;
    public int statsCnt;
    public int statsPrd;
    public long now;
    public long lastStatsTstamp;
    public long nextStatsTstamp;

    public String checkGetStats() {
        outSmsCnt++;
        now = System.currentTimeMillis();
        if (statsCnt > 0 && outSmsCnt >= nextOutSmsCnt) {
            return getResetStats("cnt:" + statsCnt);
        } else if (statsPrd > 0 && now >= nextStatsTstamp) {
            return getResetStats("prd:" + statsPrd);
        }
        return null;
    }

    public String getResetStats(String reason) {
        int reasonTotal = outSmsCnt - lastOutSmsCnt;
        float rate = (reasonTotal * 1000) / (float) (now - lastStatsTstamp);
        String ret = "Stats(" + reason + "): total:" + outSmsCnt + " currTotal:" + reasonTotal + " rate:" + rate + "tps"; //, retries: "+retriesCnt;
        resetStats();
        return ret;
    }

    public void resetStats() {
        lastOutSmsCnt = outSmsCnt;
        nextOutSmsCnt = outSmsCnt + statsCnt;
        lastStatsTstamp = now;
        nextStatsTstamp = now + statsPrd * 1000;
    }

    public void init(SendiumConfigurationProvider cp) {
        configStatsCnt(cp);
        configStatsPrd(cp);
        //retriesCnt = 0;
        outSmsCnt = 0;
        //
        lastOutSmsCnt = outSmsCnt;
        nextOutSmsCnt = outSmsCnt + statsCnt;
        //
        now = System.currentTimeMillis();
        lastStatsTstamp = now;
        nextStatsTstamp = now + statsPrd * 1000;
    }

    public void configStatsCnt(SendiumConfigurationProvider cp) {
        statsCnt = cp.getIntPrpt(statsCount);
        if (statsCnt < 0) {
            statsCnt = 0;
        }
    }

    public void configStatsPrd(SendiumConfigurationProvider cp) {
        statsPrd = cp.getIntPrpt(statsPeriod);
        if (statsPrd < 0) {
            statsPrd = 0;
        }
    }

    public void doPropertyChange(SendiumConfigurationProvider cp, PropertyChangeEvent evt) {
        String key = evt.getKey();
        if (key.equals(statsCount[0])) {
            configStatsCnt(cp);
        } else if (key.equals(statsPeriod[0])) {
            configStatsPrd(cp);
        }
    }

}

