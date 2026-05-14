package gr.cytech.sendium.util;

import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public class Sleeper {
    private final String name;

    public Sleeper() {
        this(Sleeper.class.getName());
    }

    public Sleeper(String name) {
        this.name = name;
    }

    public void sleep(long period, TimeUnit timeUnit) {
        if (period <= 0) {
            return;
        }

        try {
            timeUnit.sleep(period);
        } catch (Exception e) {
            LoggerFactory.getLogger(name).warn("exception caught while sleeping for {}{}", period, timeUnit);
        }
    }

    public static class NoopSleeper extends Sleeper {
        @Override
        public void sleep(long period, TimeUnit timeUnit) {
        }
    }
}
