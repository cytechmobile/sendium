package gr.cytech.sendium.core.storage.memory;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryStoreMetricsTest {
    @Test
    void gaugesFollowOwnershipAndIdempotentTransitionsWithoutScanning() throws Exception {
        var metrics = new SimpleMeterRegistry();
        try {
            var pending = new MemoryPendingMessageStore<StandardMessage>(2, MemoryStoreMetricsTest::copy);
            var selected = new MemorySelectedRouterStore<>(pending, 1);
            pending.bindMetrics(metrics);
            selected.bindMetrics(metrics);
            assertThat(sources(metrics)).isNaN();
            assertThat(work(metrics, "waiting")).isNaN();
            pending.open();
            selected.open();
            var source = new SourceId(UUID.randomUUID());
            pending.admit(source, new StandardMessage());
            pending.admit(source, new StandardMessage());
            assertThat(sources(metrics)).isEqualTo(1);
            assertThat(selected.selectToRouter(1)).isEqualTo(1);
            assertThat(work(metrics, "waiting")).isEqualTo(1);
            var taken = selected.take(Duration.ZERO).orElseThrow();
            assertThat(work(metrics, "waiting")).isZero();
            assertThat(work(metrics, "routing")).isEqualTo(1);
            assertThat(selected.selectToRouter(1)).isZero();
            selected.release(taken);
            selected.release(taken);
            assertThat(work(metrics, "waiting")).isEqualTo(1);
            assertThat(work(metrics, "routing")).isZero();
            taken = selected.take(Duration.ZERO).orElseThrow();
            selected.markRouted(taken.id());
            selected.markRouted(taken.id());
            assertThat(work(metrics, "routing")).isZero();
            assertThat(work(metrics, "retained")).isEqualTo(1);
            assertThat(sources(metrics)).isEqualTo(1);
            var selection = taken.id();
            assertThatThrownBy(() -> selected.complete(selection)).isInstanceOf(RuntimeException.class);
            assertThat(work(metrics, "retained")).isEqualTo(1);
            pending.complete(Set.of(source));
            pending.complete(Set.of(source));
            assertThat(sources(metrics)).isZero();
            assertThat(work(metrics, "retained")).isEqualTo(1);
            selected.complete(selection);
            selected.complete(selection);
            assertThat(work(metrics, "retained")).isZero();
            selected.close();
            pending.close();
            assertThat(sources(metrics)).isNaN();
            assertThat(work(metrics, "retained")).isNaN();
        } finally {
            metrics.close();
        }
    }

    @Test
    void failedAdmissionKeepsActualOccupancy() {
        var metrics = new SimpleMeterRegistry();
        try {
            var pending = new MemoryPendingMessageStore<StandardMessage>(1, message -> {
                if ("fail".equals(message.body)) {
                    throw new IllegalArgumentException("snapshot failed");
                }
                return copy(message);
            });
            var selected = new MemorySelectedRouterStore<>(pending, 1);
            pending.bindMetrics(metrics);
            selected.bindMetrics(metrics);
            pending.open();
            selected.open();
            var message = new StandardMessage();
            message.body = "fail";
            assertThatThrownBy(() -> pending.admit(new SourceId(UUID.randomUUID()), message))
                    .isInstanceOf(RuntimeException.class);
            assertThat(sources(metrics)).isZero();
            message.body = "ok";
            pending.admit(new SourceId(UUID.randomUUID()), message);
            assertThatThrownBy(() -> pending.admit(new SourceId(UUID.randomUUID()), message))
                    .isInstanceOf(RuntimeException.class);
            assertThat(sources(metrics)).isEqualTo(1);
            pending.close();
            assertThat(work(metrics, "waiting")).isNaN();
            selected.close();
        } finally {
            metrics.close();
        }
    }

    @Test
    void partialSelectionFailureStillExposesPublishedWork() {
        var metrics = new SimpleMeterRegistry();
        try {
            var copies = new AtomicInteger();
            var pending = new MemoryPendingMessageStore<StandardMessage>(2, message -> {
                if (copies.incrementAndGet() == 4) {
                    throw new IllegalArgumentException("second selection snapshot failed");
                }
                return copy(message);
            });
            var selected = new MemorySelectedRouterStore<>(pending, 2);
            pending.bindMetrics(metrics);
            selected.bindMetrics(metrics);
            pending.open();
            selected.open();
            pending.admit(new SourceId(UUID.randomUUID()), new StandardMessage());
            pending.admit(new SourceId(UUID.randomUUID()), new StandardMessage());
            assertThatThrownBy(() -> selected.selectToRouter(2)).isInstanceOf(RuntimeException.class);
            assertThat(sources(metrics)).isEqualTo(2);
            assertThat(work(metrics, "waiting")).isEqualTo(1);
            assertThat(selected.selectToRouter(2)).isEqualTo(1);
            assertThat(work(metrics, "waiting")).isEqualTo(2);
            selected.close();
            pending.close();
        } finally {
            metrics.close();
        }
    }

    @Test
    void duplicateMultipartSourcesOccupyPendingButDoNotBecomeIndependentSelections() {
        var metrics = new SimpleMeterRegistry();
        try {
            var pending = new MemoryPendingMessageStore<StandardMessage>(3, MemoryStoreMetricsTest::copy);
            var selected = new MemorySelectedRouterStore<>(pending, 3);
            pending.bindMetrics(metrics);
            selected.bindMetrics(metrics);
            pending.open();
            selected.open();
            var part = new StandardMessage();
            part.owner_id = "account";
            part.systemId = "system";
            part.from = "sender";
            part.to = "recipient";
            part.binheader = "0500037F0201";
            var firstId = new SourceId(UUID.randomUUID());
            part.serial = firstId.value().toString();
            var first = pending.admitPart(firstId, part, Duration.ofMinutes(1));
            var duplicateId = new SourceId(UUID.randomUUID());
            part.serial = duplicateId.value().toString();
            var duplicate = pending.admitPart(duplicateId, part, Duration.ofMinutes(1));
            assertThat(duplicate.duplicate()).isTrue();
            assertThat(sources(metrics)).isEqualTo(2);
            assertThat(selected.selectToRouter(3)).isZero();
            part.binheader = "0500037F0202";
            var lastId = new SourceId(UUID.randomUUID());
            part.serial = lastId.value().toString();
            var last = pending.admitPart(lastId, part, Duration.ofMinutes(1));
            pending.makeHeldReady(Set.of(first.source(), duplicate.source(), last.source()), part);
            assertThat(selected.selectToRouter(3)).isEqualTo(1);
            assertThat(work(metrics, "waiting")).isEqualTo(1);
            assertThat(sources(metrics)).isEqualTo(3);
            selected.close();
            pending.close();
        } finally {
            metrics.close();
        }
    }

    private static double sources(SimpleMeterRegistry metrics) {
        return metrics.get("sendium.sms.pending.sources").tag("backend", "memory").gauge().value();
    }

    private static double work(SimpleMeterRegistry metrics, String state) {
        return metrics.get("sendium.sms.selected.work").tag("state", state).gauge().value();
    }

    private static StandardMessage copy(StandardMessage message) {
        try {
            return (StandardMessage) message.clone();
        } catch (CloneNotSupportedException failure) {
            throw new AssertionError(failure);
        }
    }
}
