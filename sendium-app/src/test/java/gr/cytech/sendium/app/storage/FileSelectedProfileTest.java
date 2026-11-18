package gr.cytech.sendium.app.storage;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.DefaultOutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.file.FilePendingMessageStore;
import gr.cytech.sendium.core.storage.file.FileSelectedRouterStore;
import gr.cytech.sendium.core.storage.memory.MemoryRoutedWorkStore;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

class FileSelectedProfileTest {
    @TempDir Path root;

    @Test
    void readinessWaitsForSelectedReconciliation() throws Exception {
        var pending = new FilePendingMessageStore(root, 10);
        var selected = spy(new FileSelectedRouterStore(pending, 2));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test startup gate timed out");
            }
            return call.callRealMethod();
        }).when(selected).open();
        try (var coordinator = new DefaultOutboundCoordinator<>(pending, selected,
                new MemoryRoutedWorkStore<StandardMessage>(10, value -> PendingMessageData.capture(value).restore()));
                var executor = Executors.newSingleThreadExecutor()) {
            var readiness = new MessageStorageReadiness();
            readiness.coordinator = coordinator;
            var startup = executor.submit(coordinator::start);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(readiness.call().getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
            } finally {
                release.countDown();
            }
            startup.get(10, TimeUnit.SECONDS);
            assertThat(readiness.call().getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        }
    }

    @Test
    void selectedOperationMetricLabelsAreBounded() throws Exception {
        var config = new SmallRyeConfigBuilder().withDefaultValue(MessageStorageProfile.PENDING_PATH, root.toString()).build();
        var metrics = new SimpleMeterRegistry();
        try (var coordinator = StandaloneOutboundPipeline.coordinator(new MessageStorageProfile("file", "file", "memory"), config, metrics)) {
            var message = message();
            coordinator.accept(source(message), message);
            coordinator.selectToRouter(1);
            var selected = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            coordinator.recordToRouted(selected, new Destination<>("provider", selected.message()));
            var routed = coordinator.takeFromRouted("provider", Duration.ZERO).orElseThrow();
            coordinator.complete(routed.id(), CompletableFuture.completedFuture(null)).toCompletableFuture().join();
            assertThatThrownBy(() -> coordinator.selectToRouter(0)).isInstanceOf(IllegalArgumentException.class);
            var meters = metrics.getMeters().stream().filter(meter -> meter.getId().getTag("operation") != null &&
                    meter.getId().getTag("operation").startsWith("selected_")).toList();
            assertThat(meters).isNotEmpty();
            for (var meter : meters) {
                assertThat(meter.getId().getTags()).extracting(tag -> tag.getKey())
                        .containsExactlyInAnyOrder("backend", "operation", "outcome", "reason");
                assertThat(meter.getId().getTag("backend")).isEqualTo("file");
                assertThat(meter.getId().getTags().toString()).doesNotContain(message.serial, root.toString(), message.body);
            }
            assertThat(metrics.get(FilePendingMessageStore.OPERATION_METRIC).tag("operation", "selected_open")
                    .tag("outcome", "success").timer().count()).isEqualTo(1);
            assertThat(metrics.get(FilePendingMessageStore.OPERATION_METRIC).tag("operation", "selected_select")
                    .tag("outcome", "error").tag("reason", "OTHER").timer().count()).isEqualTo(1);
        } finally {
            metrics.close();
        }
    }

    @Test
    void rejectsDowngradeWithOutstandingSelections() throws Exception {
        var config = new SmallRyeConfigBuilder().withDefaultValue(MessageStorageProfile.PENDING_PATH, root.toString()).build();
        var file = new MessageStorageProfile("file", "file", "memory");
        var memory = new MessageStorageProfile("file", "memory", "memory");
        var message = message();
        gr.cytech.sendium.core.outbound.OutboundWork.SelectionId id;
        try (var coordinator = StandaloneOutboundPipeline.coordinator(file, config)) {
            coordinator.accept(source(message), message);
            coordinator.selectToRouter(1);
            id = coordinator.takeForRouting(Duration.ZERO).orElseThrow().id();
        }
        assertThatThrownBy(() -> StandaloneOutboundPipeline.coordinator(memory, config))
                .hasMessageContaining("reason:outstandingSelectedWork").hasMessageContaining("fallback:none");
        try (var restored = StandaloneOutboundPipeline.coordinator(file, config)) {
            var selected = restored.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(selected.id()).isEqualTo(id);
            assertThat(selected.sources()).isEqualTo(Set.of(source(message)));
            restored.discard(id, CompletableFuture.completedFuture(null)).toCompletableFuture().join();
        }
        try (var drained = StandaloneOutboundPipeline.coordinator(memory, config)) {
            assertThat(drained.isReady()).isTrue();
            assertThat(drained.selectToRouter(1)).isZero();
        }
    }

    private static StandardMessage message() {
        var message = new StandardMessage();
        message.serial = UUID.randomUUID().toString();
        message.body = "private accepted payload";
        return message;
    }

    private static SourceId source(StandardMessage message) {
        return new SourceId(UUID.fromString(message.serial));
    }
}
