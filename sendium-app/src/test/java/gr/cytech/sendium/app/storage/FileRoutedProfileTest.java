package gr.cytech.sendium.app.storage;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.DefaultOutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.file.FilePendingMessageStore;
import gr.cytech.sendium.core.storage.file.FileRoutedWorkStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

class FileRoutedProfileTest {
    @TempDir Path root;

    @Test
    void composesExactlyTheSupportedProfiles() throws Exception {
        for (String pending : new String[]{"memory", "file"}) {
            for (String selected : new String[]{"memory", "file"}) {
                for (String routed : new String[]{"memory", "file"}) {
                    if (pending.equals("memory") && (!selected.equals("memory") || !routed.equals("memory"))) {
                        assertThatThrownBy(() -> new MessageStorageProfile(pending, selected, routed))
                                .isInstanceOf(IllegalArgumentException.class);
                        continue;
                    }
                    var profile = new MessageStorageProfile(pending, selected, routed);
                    var config = new SmallRyeConfigBuilder().withDefaultValue(MessageStorageProfile.PENDING_PATH,
                            root.resolve(profile.toString().replace('/', '-')).toString()).build();
                    try (var coordinator = StandaloneOutboundPipeline.coordinator(profile, config)) {
                        assertThat(coordinator.isReady()).isTrue();
                        assertBackend(coordinator, "pending", pending);
                        assertBackend(coordinator, "router", selected);
                        assertBackend(coordinator, "routed", routed);
                    }
                }
            }
        }
    }

    private static void assertBackend(Object coordinator, String fieldName, String backend) throws Exception {
        var field = coordinator.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        var stage = (OutboundStage) field.get(coordinator);
        assertThat(stage.status().backend()).isEqualTo(backend);
        assertThat(stage.status().durable()).isEqualTo(backend.equals("file"));
    }

    @Test
    void readinessWaitsForRoutedReconciliation() throws Exception {
        var pending = new FilePendingMessageStore(root, 10);
        var routed = spy(new FileRoutedWorkStore(pending, 10));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test startup gate timed out");
            }
            return call.callRealMethod();
        }).when(routed).open();
        try (var coordinator = new DefaultOutboundCoordinator<>(pending, new MemorySelectedRouterStore<>(pending, 2), routed);
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
    void routedOperationMetricLabelsAreBounded() throws Exception {
        var config = new SmallRyeConfigBuilder().withDefaultValue(MessageStorageProfile.PENDING_PATH, root.toString()).build();
        var metrics = new SimpleMeterRegistry();
        try (var coordinator = StandaloneOutboundPipeline.coordinator(new MessageStorageProfile("file", "memory", "file"), config, metrics)) {
            var message = message();
            coordinator.accept(source(message), message);
            coordinator.selectToRouter(1);
            var selected = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            coordinator.recordToRouted(selected, new Destination<>("provider", selected.message()));
            var work = coordinator.takeFromRouted("provider", Duration.ZERO).orElseThrow();
            coordinator.returnToRouted(work);
            work = coordinator.takeFromRouted("provider", Duration.ZERO).orElseThrow();
            coordinator.complete(work.id(), CompletableFuture.completedFuture(null)).toCompletableFuture().join();
            var meters = metrics.getMeters().stream().filter(meter -> meter.getId().getTag("operation") != null &&
                    meter.getId().getTag("operation").startsWith("routed_")).toList();
            assertThat(meters).extracting(meter -> meter.getId().getTag("operation"))
                    .contains("routed_open", "routed_record", "routed_take", "routed_release", "routed_complete", "routed_forget");
            for (var meter : meters) {
                assertThat(meter.getId().getTags()).extracting(tag -> tag.getKey())
                        .containsExactlyInAnyOrder("backend", "operation", "outcome", "reason");
                assertThat(meter.getId().getTag("backend")).isEqualTo("file");
                assertThat(meter.getId().getTags().toString()).doesNotContain(message.serial, message.body, root.toString());
            }
        }
    }

    @Test
    void missingWorkerDoesNotChangeRecordedAssignment() throws Exception {
        var config = new SmallRyeConfigBuilder().withDefaultValue(MessageStorageProfile.PENDING_PATH, root.toString()).build();
        var profile = new MessageStorageProfile("file", "memory", "file");
        try (var coordinator = StandaloneOutboundPipeline.coordinator(profile, config)) {
            for (String destination : new String[]{"removed-provider", "live-provider"}) {
                var message = message();
                coordinator.accept(source(message), message);
                coordinator.selectToRouter(1);
                var selected = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
                coordinator.recordToRouted(selected, new Destination<>(destination, selected.message()));
            }
        }
        try (var coordinator = StandaloneOutboundPipeline.coordinator(profile, config)) {
            assertThat(coordinator.isReady()).isTrue();
            assertThat(coordinator.takeForRouting(Duration.ZERO)).isEmpty();
            var live = coordinator.takeFromRouted("live-provider", Duration.ZERO).orElseThrow();
            coordinator.complete(live.id(), CompletableFuture.completedFuture(null)).toCompletableFuture().join();
            // No worker takes removed-provider work; another destination's work still completes.
        }
        try (var coordinator = StandaloneOutboundPipeline.coordinator(profile, config)) {
            assertThat(coordinator.takeFromRouted("live-provider", Duration.ZERO)).isEmpty();
            var retained = coordinator.takeFromRouted("removed-provider", Duration.ZERO).orElseThrow();
            assertThat(retained.destination()).isEqualTo("removed-provider");
            coordinator.complete(retained.id(), CompletableFuture.completedFuture(null)).toCompletableFuture().join();
        }
    }

    @Test
    void rejectsDowngradeWithOutstandingRoutedWork() throws Exception {
        var config = new SmallRyeConfigBuilder().withDefaultValue(MessageStorageProfile.PENDING_PATH, root.toString()).build();
        var durable = new MessageStorageProfile("file", "memory", "file");
        try (var coordinator = StandaloneOutboundPipeline.coordinator(durable, config)) {
            var message = message();
            coordinator.accept(source(message), message);
            coordinator.selectToRouter(1);
            var selected = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            coordinator.recordToRouted(selected, new Destination<>("provider", selected.message()));
        }
        assertThatThrownBy(() -> StandaloneOutboundPipeline.coordinator(new MessageStorageProfile("file", "memory", "memory"), config))
                .isInstanceOf(OutboundStorageException.class).hasMessageContaining("reason:outstandingRoutedWork");
        try (var coordinator = StandaloneOutboundPipeline.coordinator(durable, config)) {
            var work = coordinator.takeFromRouted("provider", Duration.ZERO).orElseThrow();
            coordinator.complete(work.id(), CompletableFuture.completedFuture(null)).toCompletableFuture().join();
        }
        try (var coordinator = StandaloneOutboundPipeline.coordinator(new MessageStorageProfile("file", "memory", "memory"), config)) {
            assertThat(coordinator.isReady()).isTrue();
        }
    }

    private static StandardMessage message() {
        var message = new StandardMessage();
        message.serial = UUID.randomUUID().toString();
        message.body = "secret-message-body";
        return message;
    }

    @Test
    void recoveryDiagnosticsAreStructuredAndPayloadFree() throws Exception {
        var config = new SmallRyeConfigBuilder().withDefaultValue(MessageStorageProfile.PENDING_PATH, root.toString()).build();
        var profile = new MessageStorageProfile("file", "memory", "file");
        try (var coordinator = StandaloneOutboundPipeline.coordinator(profile, config)) {
            var message = message();
            coordinator.accept(source(message), message);
            coordinator.selectToRouter(1);
            var selected = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            coordinator.recordToRouted(selected, new Destination<>("provider", selected.message()));
        }
        Path corrupted;
        try (var files = Files.walk(root.resolve("routed"))) {
            corrupted = files.filter(path -> path.toString().endsWith(".cbor")).findFirst().orElseThrow();
        }
        Files.write(corrupted, new byte[0]);
        assertThatThrownBy(() -> StandaloneOutboundPipeline.coordinator(profile, config))
                .isInstanceOf(OutboundStorageException.class)
                .hasMessageContaining("routedRecovery|error|reason:invalidEnvelope")
                .hasMessageNotContaining("secret-message-body")
                .hasMessageNotContaining(root.toString());
        assertThat(Files.exists(corrupted)).isTrue();
    }

    private static SourceId source(StandardMessage message) {
        return new SourceId(UUID.fromString(message.serial));
    }
}
