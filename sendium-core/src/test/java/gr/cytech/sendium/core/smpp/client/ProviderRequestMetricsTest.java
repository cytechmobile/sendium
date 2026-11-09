package gr.cytech.sendium.core.smpp.client;

import gr.cytech.sendium.core.message.StandardMessage;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderRequestMetricsTest {
    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    private final ProviderRequestMetrics metrics = new ProviderRequestMetrics(registry);

    @AfterEach
    void close() {
        registry.close();
    }

    @Test
    void firstResponseReleasesOccupancyAndStopsLatencyBeforeRequiredHandoff() {
        var part = new CoordinatedSmppSubmission.Part<>(new StandardMessage());
        part.startMetrics(metrics, "provider", false);
        assertThat(inFlight()).isEqualTo(1);
        clock.add(Duration.ofSeconds(2));
        assertThat(part.claimResponse(ProviderRequestMetrics.Result.ACCEPTED)).isTrue();
        var handoff = new CompletableFuture<Void>();
        part.finish(() -> handoff);
        clock.add(Duration.ofMinutes(1));
        assertThat(part.completion().toCompletableFuture()).isNotDone();
        assertThat(inFlight()).isZero();
        var timer = registry.get("sendium.sms.provider.response").tag("result", "accepted").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(part.claimResponse(ProviderRequestMetrics.Result.TIMEOUT)).isFalse();
        handoff.complete(null);
        assertThat(timer.count()).isEqualTo(1);
        assertThat(registry.get("sendium.sms.provider.response").tag("result", "timeout").timer().count()).isZero();
    }

    @Test
    void sharedProviderStateJoinsInitialAndReplacementRequestsWithoutAnotherMessageIndex() {
        var initial = new CoordinatedSmppSubmission.Part<>(new StandardMessage());
        var replacement = new CoordinatedSmppSubmission.Part<>(new StandardMessage());
        initial.startMetrics(metrics, "provider", false);
        replacement.startMetrics(metrics, "provider", true);
        assertThat(inFlight()).isEqualTo(2);
        assertThat(registry.get("sendium.sms.provider.attempts").tag("kind", "initial").counter().count()).isEqualTo(1);
        assertThat(registry.get("sendium.sms.provider.attempts").tag("kind", "retry").counter().count()).isEqualTo(1);
        initial.claimResponse(ProviderRequestMetrics.Result.REJECTED);
        assertThat(inFlight()).isEqualTo(1);
        replacement.claimResponse(ProviderRequestMetrics.Result.ACCEPTED);
        assertThat(inFlight()).isZero();
    }

    @Test
    void syntheticUnsentPartsDoNotRegisterRequestMetrics() {
        var part = new CoordinatedSmppSubmission.Part<>(new StandardMessage());
        assertThat(part.claimResponse()).isTrue();
        part.finish(() -> CompletableFuture.completedStage(null));
        assertThat(registry.getMeters()).isEmpty();
    }

    private double inFlight() {
        return registry.get("sendium.sms.provider.in.flight").tag("provider", "provider").gauge().value();
    }
}
