package gr.cytech.sendium.core.smpp.server;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class Statistic {

    private final AtomicInteger counter;
    private final AtomicInteger lastStatsCounter;
    private final AtomicInteger nextStatsCounter;
    private final AtomicBoolean flag;
    private final String name;
    private final String unit;
    private volatile long now;
    private volatile long lastStatsTstamp;
    private volatile long nextStatsTstamp;
    private volatile int count;
    private volatile long period;

    public Statistic(String name, String unit, int count, int period) {
        this.name = name;
        this.unit = unit;
        this.count = count;
        this.period = period;

        this.counter = new AtomicInteger();
        this.lastStatsCounter = new AtomicInteger();
        this.nextStatsCounter = new AtomicInteger();
        this.flag = new AtomicBoolean();
    }

    public int get() {
        return counter.get();
    }

    public String checkGetStats() {
        now = System.currentTimeMillis();
        counter.incrementAndGet();

        if (period > 0 && now >= nextStatsTstamp) {
            if (flag.compareAndSet(false, true)) {
                return getStatsAsString(String.format("prd:%s", period));
            }
        } else if (count > 0 && counter.get() >= nextStatsCounter.get()) {
            if (flag.compareAndSet(false, true)) {
                return getStatsAsString(String.format("cnt:%s", count));
            }
        }

        return null;
    }

    private String getStatsAsString(String reason) {
        double rate = ((counter.get() - lastStatsCounter.get()) * 1000) / (double) (now - lastStatsTstamp);
        String ret = String.format("Stats(%s): %s: total: %s, rate: %s %s/sec", reason, name, counter.get(), rate, unit);
        resetStats();
        return ret;
    }

    private void resetStats() {
        lastStatsCounter.set(counter.get());
        nextStatsCounter.set(counter.get() + count);
        lastStatsTstamp = now;
        nextStatsTstamp = now + period * 1000;
        flag.compareAndSet(true, false);
    }

    public void init() {
        counter.set(0);
        lastStatsCounter.set(counter.get());
        nextStatsCounter.set(counter.get() + count);

        now = System.currentTimeMillis();
        lastStatsTstamp = now;
        nextStatsTstamp = now + period * 1000;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public void setPeriod(long period) {
        this.period = period;
    }

}
