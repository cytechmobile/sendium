package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilePendingMetricsTest {
    @TempDir Path root;

    @Test
    void recordsLatencyAndTypedErrorsWithoutSourcePayloadOrPathLabels() {
        var registry = new SimpleMeterRegistry();
        try (var pending = new FilePendingMessageStore(root, 1, Clock.systemUTC(), registry)) {
            pending.open();
            var message = new StandardMessage();
            message.serial = UUID.randomUUID().toString();
            message.body = "private-payload";
            var source = new SourceId(UUID.fromString(message.serial));
            pending.admit(source, message);
            for (int index = 0; index < 10; index++) {
                assertThatThrownBy(() -> pending.admit(new SourceId(UUID.randomUUID()), message))
                        .isInstanceOf(gr.cytech.sendium.core.storage.OutboundStorageException.class);
            }
            pending.find(source);
            pending.complete(Set.of(source));
            assertThat(registry.get(FilePendingMessageStore.OPERATION_METRIC).tag("operation", "admit")
                    .tag("outcome", "success").timer().count()).isEqualTo(1);
            assertThat(registry.get(FilePendingMessageStore.OPERATION_METRIC).tag("operation", "admit")
                    .tag("outcome", "error").tag("reason", "CAPACITY_EXCEEDED").timer().count()).isEqualTo(10);
            assertThat(registry.get(FilePendingMessageStore.OPERATION_METRIC).tag("operation", "admit")
                    .tag("outcome", "success").timer().totalTime(TimeUnit.NANOSECONDS)).isPositive();
            assertThat(registry.get(FilePendingMessageStore.OPERATION_METRIC).tag("operation", "complete").timer().count()).isEqualTo(1);
            int meters = registry.getMeters().size();
            pending.status();
            assertThat(registry.getMeters()).hasSize(meters);
            registry.getMeters().forEach(meter -> {
                assertThat(meter.getId().getTags()).extracting(tag -> tag.getKey())
                        .containsExactlyInAnyOrder("backend", "operation", "outcome", "reason");
                assertThat(meter.getId().toString()).doesNotContain(message.serial, message.body, root.toString());
            });
        } finally {
            registry.close();
        }
    }

    @Test
    void failedStartupRecordsItsTypedErrorWithoutAPathLabel() throws Exception {
        var registry = new SimpleMeterRegistry();
        var invalidRoot = Files.createFile(root.resolve("not-a-directory"));
        try (var pending = new FilePendingMessageStore(invalidRoot, 1, Clock.systemUTC(), registry)) {
            assertThatThrownBy(pending::open).isInstanceOf(gr.cytech.sendium.core.storage.OutboundStorageException.class);
            var timer = registry.get(FilePendingMessageStore.OPERATION_METRIC).tag("operation", "open")
                    .tag("outcome", "error").timer();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.getId().getTag("reason")).isNotEqualTo("NONE");
            assertThat(timer.getId().toString()).doesNotContain(invalidRoot.toString());
        } finally {
            registry.close();
        }
    }
}
