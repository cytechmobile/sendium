package com.google.common.util.concurrent;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/** Test-only clock bridge: exercise Guava's actual pacing algorithm without wall-clock sleeps. */
public final class TestRateLimiters {
    private TestRateLimiters() { }

    public static RateLimiter create(double rate, LongSupplier nanos, LongConsumer sleepNanos) {
        long origin = nanos.getAsLong();
        return RateLimiter.create(rate, new RateLimiter.SleepingStopwatch() {
            @Override protected long readMicros() { return (nanos.getAsLong() - origin) / 1000; }
            @Override protected void sleepMicrosUninterruptibly(long micros) {
                if (micros > 0) { sleepNanos.accept(micros * 1000); }
            }
        });
    }
}
