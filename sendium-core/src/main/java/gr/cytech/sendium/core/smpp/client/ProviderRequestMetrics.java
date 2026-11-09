package gr.cytech.sendium.core.smpp.client;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared per configured provider, including across replacement worker instances. No per-message index. */
@ApplicationScoped
public class ProviderRequestMetrics {
    enum Result {
        ACCEPTED("accepted"), REJECTED("rejected"), TIMEOUT("timeout"),
        TRANSPORT_ERROR("transport_error"), PROTOCOL_ERROR("protocol_error");

        private final String label;

        Result(String label) {
            this.label = label;
        }
    }

    private final MeterRegistry registry;
    private final Map<String, Meters> providers = new ConcurrentHashMap<>();

    @Inject
    public ProviderRequestMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    Attempt start(String provider, boolean retry) {
        Meters meters = providers.computeIfAbsent(provider, this::meters);
        (retry ? meters.retry : meters.initial).increment();
        meters.inFlight.incrementAndGet();
        return new Attempt(meters, Timer.start(registry));
    }

    private Meters meters(String provider) {
        var inFlight = new AtomicInteger();
        Gauge.builder("sendium.sms.provider.in.flight", inFlight, AtomicInteger::get)
                .description("Provider requests awaiting their first response or local failure")
                .tag("provider", provider).register(registry);
        var timers = new EnumMap<Result, Timer>(Result.class);
        for (Result result : Result.values()) {
            timers.put(result, Timer.builder("sendium.sms.provider.response")
                    .description("Provider request latency excluding subsequent required handoffs")
                    .tags("provider", provider, "result", result.label).register(registry));
        }
        return new Meters(registry.counter("sendium.sms.provider.attempts", "provider", provider, "kind", "initial"),
                registry.counter("sendium.sms.provider.attempts", "provider", provider, "kind", "retry"), inFlight, timers);
    }

    /** Completion is guarded by the existing per-part response claim, not by another message map. */
    static final class Attempt {
        private final Meters meters;
        private final Timer.Sample sample;

        private Attempt(Meters meters, Timer.Sample sample) {
            this.meters = meters;
            this.sample = sample;
        }

        void complete(Result result) {
            sample.stop(meters.timers.get(result));
            meters.inFlight.decrementAndGet();
        }
    }

    private record Meters(Counter initial, Counter retry, AtomicInteger inFlight, Map<Result, Timer> timers) { }
}
