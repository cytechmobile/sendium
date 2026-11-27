package gr.cytech.sendium.core.outbound;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.outbound.OutboundWork.WorkId;
import gr.cytech.sendium.core.storage.RoutedWorkStore;
import gr.cytech.sendium.core.storage.SelectedRouterStore;
import gr.cytech.sendium.core.storage.file.FilePendingMessageStore;
import gr.cytech.sendium.core.storage.file.FileRoutedWorkStore;
import gr.cytech.sendium.core.storage.file.FileSelectedRouterStore;
import gr.cytech.sendium.core.storage.memory.MemoryRoutedWorkStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.dataformat.cbor.CBORMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Forced child death at persisted stage boundaries, with no graceful close in the writer. */
@Timeout(45)
class ForcedStageRestartTest {
    private static final SourceId SOURCE = new SourceId(UUID.fromString("a1000000-0000-0000-0000-000000000001"));
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {"file/file/memory", "file/file/file"})
    void selectionCommitSurvivesForcedDeath(String profile) throws Exception {
        crashAt(profile, "selected");
        try (var recovered = new Harness(root, profile)) {
            recovered.coordinator.start();
            assertThat(recovered.coordinator.selectToRouter(2)).isZero();
            var selected = recovered.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(selected.id().value().toString()).isEqualTo(Files.readString(root.resolve("selection-id")));
            assertThat(selected.message().body).isEqualTo("accepted snapshot");
            assertThat(selected.sources()).containsExactly(SOURCE);
            recovered.coordinator.discard(selected.id(), CompletableFuture.completedFuture(null)).toCompletableFuture().join();
            assertThat(recovered.pending.sourcesAfter(null, 2)).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"file/memory/file", "file/file/file"})
    void routeCommitSurvivesForcedDeath(String profile) throws Exception {
        crashAt(profile, "routed");
        try (var recovered = new Harness(root, profile)) {
            recovered.coordinator.start();
            assertThat(recovered.coordinator.selectToRouter(2)).isZero();
            assertThat(recovered.coordinator.takeForRouting(Duration.ZERO)).isEmpty();
            assertThat(recovered.coordinator.takeFromRouted("new-provider", Duration.ZERO)).isEmpty();
            var work = recovered.coordinator.takeFromRouted("old-provider", Duration.ZERO).orElseThrow();
            assertThat(work.id().value().toString()).isEqualTo(Files.readString(root.resolve("work-id")));
            assertThat(work.message().body).isEqualTo("routing snapshot");
            recovered.coordinator.complete(work.id(), CompletableFuture.completedFuture(null)).toCompletableFuture().join();
            assertThat(recovered.pending.sourcesAfter(null, 2)).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"file/file/memory", "file/memory/file", "file/file/file"})
    void interruptedCleanupDoesNotResurrectWork(String profile) throws Exception {
        crashAt(profile, "cleanup");
        try (var recovered = new Harness(root, profile)) {
            recovered.coordinator.start();
            assertThat(recovered.coordinator.isReady()).isTrue();
            assertThat(recovered.pending.sourcesAfter(null, 2)).isEmpty();
            assertThat(recovered.coordinator.selectToRouter(2)).isZero();
            assertThat(recovered.coordinator.takeForRouting(Duration.ZERO)).isEmpty();
            assertThat(recovered.coordinator.takeFromRouted("old-provider", Duration.ZERO)).isEmpty();
            for (String namespace : List.of("accepted", "selected", "routed", "routed-terminal", "terminal")) {
                try (var files = Files.walk(root.resolve("spool").resolve(namespace))) {
                    assertThat(files.filter(Files::isRegularFile)).isEmpty();
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"file/file/memory", "file/memory/file", "file/file/file"})
    void providerAmbiguityRetainsRecoverableWork(String profile) throws Exception {
        crashAt(profile, "provider");
        try (var recovered = new Harness(root, profile)) {
            recovered.coordinator.start();
            if (profile.endsWith("memory")) {
                var selected = recovered.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
                recovered.coordinator.recordToRouted(selected, new Destination<>("old-provider", selected.message()));
            }
            var work = recovered.coordinator.takeFromRouted("old-provider", Duration.ZERO).orElseThrow();
            if (profile.endsWith("file")) {
                assertThat(work.id()).isEqualTo(new WorkId(UUID.fromString(Files.readString(root.resolve("work-id")))));
            }
            Files.writeString(root.resolve("provider-effects"), "submitted\n", StandardOpenOption.APPEND);
            assertThat(Files.readAllLines(root.resolve("provider-effects"))).containsExactly("submitted", "submitted");
            var handoff = new CompletableFuture<Void>();
            var completion = recovered.coordinator.complete(work.id(), handoff).toCompletableFuture();
            assertThat(completion).isNotDone();
            assertThat(recovered.pending.find(SOURCE)).isPresent();
            handoff.complete(null);
            completion.join();
            assertThat(recovered.pending.find(SOURCE)).isEmpty();
        }
    }

    private void crashAt(String profile, String boundary) throws Exception {
        Path executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path arguments = root.resolve("java.args");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Files.writeString(arguments, "-cp\n\"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n");
        Path log = root.resolve("child.log");
        var child = new ProcessBuilder(executable.toString(), "@" + arguments, CrashWriter.class.getName(),
                root.toString(), profile, boundary).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(root.resolve("ready")) && child.isAlive() && System.nanoTime() < deadline) {
                child.waitFor(20, TimeUnit.MILLISECONDS);
            }
            assertThat(Files.exists(root.resolve("ready"))).withFailMessage("Child did not reach %s/%s:%n%s",
                    profile, boundary, Files.readString(log)).isTrue();
            assertThat(child.isAlive()).isTrue();
        } finally {
            child.destroyForcibly();
            assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(child.exitValue()).isNotZero();
    }

    public static final class CrashWriter {
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]);
            var harness = new Harness(root, args[1]);
            harness.coordinator.start();
            var message = new StandardMessage();
            message.serial = SOURCE.value().toString();
            message.body = "accepted snapshot";
            harness.coordinator.accept(SOURCE, message);
            harness.coordinator.selectToRouter(1);
            var selected = harness.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            Files.writeString(root.resolve("selection-id"), selected.id().value().toString());
            selected.message().body = "routing snapshot";
            if (!args[2].equals("selected")) {
                var work = harness.coordinator.recordToRouted(selected, new Destination<>("old-provider", selected.message()));
                Files.writeString(root.resolve("work-id"), work.id().value().toString());
                if (args[2].equals("provider")) {
                    harness.coordinator.takeFromRouted("old-provider", Duration.ZERO).orElseThrow();
                    Files.writeString(root.resolve("provider-effects"), "submitted\n");
                    harness.coordinator.complete(work.id(), new CompletableFuture<>());
                } else if (args[2].equals("cleanup")) {
                    harness.coordinator.takeFromRouted("old-provider", Duration.ZERO).orElseThrow();
                    harness.routed.complete(work.id());
                    // A physical fixture represents an authorized cleanup after its first source deletion.
                    var mapper = new CBORMapper();
                    var intent = mapper.createObjectNode();
                    UUID id = UUID.randomUUID();
                    intent.put("schemaVersion", 1);
                    intent.put("intentId", id.toString());
                    intent.putArray("sources").add(SOURCE.value().toString());
                    intent.putArray("closedGroups");
                    var selections = intent.putArray("selections");
                    if (args[1].startsWith("file/file/")) {
                        selections.add(selected.id().value().toString());
                    }
                    var routed = intent.putArray("routedWork");
                    if (args[1].endsWith("file")) {
                        routed.add(work.id().value().toString());
                    }
                    Files.write(root.resolve("spool/terminal").resolve(id + ".cbor"), mapper.writeValueAsBytes(intent));
                    Files.delete(root.resolve("spool/accepted/2/a1").resolve(SOURCE.value() + ".cbor"));
                }
            }
            Files.writeString(root.resolve("ready"), args[2]);
            new CountDownLatch(1).await();
        }
    }

    private static final class Harness implements AutoCloseable {
        private final FilePendingMessageStore pending;
        private final RoutedWorkStore<StandardMessage> routed;
        private final DefaultOutboundCoordinator<StandardMessage> coordinator;

        private Harness(Path root, String profile) {
            pending = new FilePendingMessageStore(root.resolve("spool"), 10);
            SelectedRouterStore<StandardMessage> selected = profile.startsWith("file/file/") ?
                    new FileSelectedRouterStore(pending, 2) : new MemorySelectedRouterStore<>(pending, 2);
            routed = profile.endsWith("file") ? new FileRoutedWorkStore(pending, 10, 2) :
                    new MemoryRoutedWorkStore<>(10, message -> PendingMessageData.capture(message).restore());
            coordinator = new DefaultOutboundCoordinator<>(pending, selected, routed, 2);
        }

        @Override
        public void close() {
            coordinator.close();
        }
    }
}
