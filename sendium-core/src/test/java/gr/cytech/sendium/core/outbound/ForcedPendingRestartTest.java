package gr.cytech.sendium.core.outbound;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.file.FilePendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemoryRoutedWorkStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import gr.cytech.sendium.routing.StandardOutboundDispatch;
import gr.cytech.sendium.routing.StandardRoutingManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real child-process death; no close(), shutdown hook, or graceful ownership restoration in the writer. */
@Timeout(30)
class ForcedPendingRestartTest {
    private static final Instant START = Instant.parse("2026-11-09T12:00:00Z");
    @TempDir Path root;

    enum Boundary {
        ADMITTED, SELECTED, ROUTED, PROVIDER_PART_PENDING, DLR_HANDOFF_FAILED, TERMINAL_COMPLETE,
        MULTIPART_COMPLETE, MULTIPART_INCOMPLETE
    }

    @ParameterizedTest
    @EnumSource(value = Boundary.class, names = {"ADMITTED", "SELECTED", "ROUTED", "PROVIDER_PART_PENDING", "DLR_HANDOFF_FAILED"})
    void retainedSourcesReselectOriginalPayloadAndRerouteAfterForcedDeath(Boundary boundary) throws Exception {
        crashAt(boundary);
        try (var recovered = new Harness(root, START)) {
            recovered.coordinator.start();
            assertThat(recovered.coordinator.isReady()).isTrue();
            assertThat(recovered.coordinator.takeFromRouted("old-provider", Duration.ZERO)).isEmpty();
            assertThat(recovered.coordinator.selectToRouter(4)).isEqualTo(1);
            var selected = recovered.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(selected.message().body).isEqualTo("accepted");
            assertThat(selected.sources()).containsExactly(source("ordinary"));
            recovered.coordinator.recordToRouted(selected, new Destination<>("new-provider", selected.message()));
            var routed = recovered.coordinator.takeFromRouted("new-provider", Duration.ZERO).orElseThrow();
            if (boundary == Boundary.PROVIDER_PART_PENDING || boundary == Boundary.DLR_HANDOFF_FAILED) {
                // A test-side external-effect marker models an already submitted provider part.
                Files.writeString(root.resolve("provider-effects"), "submitted\n", StandardOpenOption.APPEND);
                assertThat(Files.readAllLines(root.resolve("provider-effects"))).containsExactly("submitted", "submitted");
            }
            var dispatch = new StandardOutboundDispatch(recovered.coordinator, new StandardRoutingManager());
            var lastPart = new CompletableFuture<Void>();
            var terminal = dispatch.finishProviderParts(routed, List.of(CompletableFuture.completedStage(null), lastPart));
            assertThat(terminal.toCompletableFuture()).isNotDone();
            assertThat(recovered.pending.find(source("ordinary"))).isPresent();
            lastPart.complete(null);
            terminal.toCompletableFuture().join();
            assertThat(recovered.pending.find(source("ordinary"))).isEmpty();
            dispatch.close();
        }
        try (var reopened = new Harness(root, START)) {
            reopened.coordinator.start();
            assertThat(reopened.coordinator.selectToRouter(4)).isZero();
        }
    }

    @Test
    void committedTerminalCleanupDoesNotResurrectAfterForcedDeath() throws Exception {
        crashAt(Boundary.TERMINAL_COMPLETE);
        try (var recovered = new Harness(root, START)) {
            recovered.coordinator.start();
            assertThat(recovered.pending.sourcesAfter(null, 4)).isEmpty();
            assertThat(recovered.coordinator.selectToRouter(4)).isZero();
        }
    }

    @Test
    void completeMultipartReassemblesOriginalOrdinalsAndRetainsDuplicateOwnership() throws Exception {
        crashAt(Boundary.MULTIPART_COMPLETE);
        try (var recovered = new Harness(root, START)) {
            recovered.coordinator.start();
            assertThat(recovered.coordinator.selectToRouter(4)).isEqualTo(1);
            var selected = recovered.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(selected.message().body).isEqualTo("AB");
            assertThat(selected.sources()).containsExactlyInAnyOrder(source("first"), source("duplicate"), source("second"));
            recovered.coordinator.discard(selected.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
            assertThat(recovered.pending.sourcesAfter(null, 4)).isEmpty();
        }
    }

    @Test
    void incompleteMultipartPreservesItsAbsoluteDeadlineAndExpiresOriginalsWithoutLosingDuplicates() throws Exception {
        crashAt(Boundary.MULTIPART_INCOMPLETE);
        try (var recovered = new Harness(root, START.plusSeconds(30))) {
            recovered.coordinator.start();
            assertThat(recovered.coordinator.selectToRouter(4)).isZero();
            var first = recovered.pending.findMultipart(source("first")).orElseThrow();
            assertThat(first.deadline()).isEqualTo(START.plusSeconds(60));
            var second = message("second", "B", "0500037F0302");
            var admitted = recovered.coordinator.acceptPart(source("second"), second, Duration.ofHours(1));
            assertThat(admitted.groupId()).isEqualTo(first.groupId());
            assertThat(admitted.deadline()).isEqualTo(first.deadline());
            recovered.coordinator.pendingMultipartRecovery().orElseThrow().accepted(source("second"));
            assertThat(recovered.coordinator.selectToRouter(4)).isZero();
        }
        try (var expired = new Harness(root, START.plusSeconds(60))) {
            expired.coordinator.start();
            assertThat(expired.coordinator.selectToRouter(4)).isEqualTo(2);
            var selections = new ArrayList<OutboundWork.Selected<StandardMessage>>();
            selections.add(expired.coordinator.takeForRouting(Duration.ZERO).orElseThrow());
            selections.add(expired.coordinator.takeForRouting(Duration.ZERO).orElseThrow());
            assertThat(selections).extracting(work -> work.message().body).containsExactlyInAnyOrder("A", "B");
            assertThat(selections).anySatisfy(work -> assertThat(work.sources())
                    .containsExactlyInAnyOrder(source("first"), source("duplicate")));
            for (var selected : selections) {
                expired.coordinator.discard(selected.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
            }
            assertThat(expired.pending.sourcesAfter(null, 4)).isEmpty();
        }
    }

    private void crashAt(Boundary boundary) throws Exception {
        var executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var log = root.resolve("child.log");
        // The dependency classpath exceeds Windows' command-line limit; use the Java launcher's argument file.
        var arguments = root.resolve("java.args");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Files.writeString(arguments, "-cp\n\"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n");
        var process = new ProcessBuilder(executable.toString(), "@" + arguments,
                CrashWriter.class.getName(), root.toString(), boundary.name())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(root.resolve("ready")) && process.isAlive() && System.nanoTime() < deadline) {
                process.waitFor(20, TimeUnit.MILLISECONDS);
            }
            assertThat(Files.exists(root.resolve("ready"))).withFailMessage("Child did not reach %s:%n%s", boundary,
                    Files.readString(log)).isTrue();
            assertThat(process.isAlive()).isTrue();
            try (var contender = new Harness(root, START)) {
                assertThatThrownBy(contender.coordinator::start).isInstanceOfSatisfying(OutboundStorageException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(OutboundStorageException.Reason.OWNERSHIP_CONFLICT));
            }
        } finally {
            process.destroyForcibly();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(process.exitValue()).isNotZero();
    }

    public static final class CrashWriter {
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]);
            Boundary boundary = Boundary.valueOf(args[1]);
            var harness = new Harness(root, START);
            harness.coordinator.start();
            if (boundary == Boundary.MULTIPART_COMPLETE || boundary == Boundary.MULTIPART_INCOMPLETE) {
                String total = boundary == Boundary.MULTIPART_COMPLETE ? "02" : "03";
                for (var part : List.of(message("first", "A", "0500037F" + total + "01"),
                        message("duplicate", "ignored duplicate", "0500037F" + total + "01"))) {
                    var source = new SourceId(UUID.fromString(part.serial));
                    harness.coordinator.acceptPart(source, part, Duration.ofSeconds(60));
                    harness.coordinator.pendingMultipartRecovery().orElseThrow().accepted(source);
                }
                if (boundary == Boundary.MULTIPART_COMPLETE) {
                    harness.coordinator.acceptPart(source("second"), message("second", "B", "0500037F0202"), Duration.ofSeconds(60));
                    harness.coordinator.pendingMultipartRecovery().orElseThrow().accepted(source("second"));
                }
            } else {
                harness.coordinator.accept(source("ordinary"), message("ordinary", "accepted", null));
                if (boundary != Boundary.ADMITTED) {
                    harness.coordinator.selectToRouter(1);
                    var selected = harness.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
                    selected.message().body = "execution-only mutation";
                    if (boundary != Boundary.SELECTED) {
                        harness.coordinator.recordToRouted(selected, new Destination<>("old-provider", selected.message()));
                        if (boundary != Boundary.ROUTED) {
                            var work = harness.coordinator.takeFromRouted("old-provider", Duration.ZERO).orElseThrow();
                            var dispatch = new StandardOutboundDispatch(harness.coordinator, new StandardRoutingManager());
                            Files.writeString(root.resolve("provider-effects"), "submitted\n");
                            var last = new CompletableFuture<Void>();
                            var completion = dispatch.finishProviderParts(work, List.of(CompletableFuture.completedStage(null), last));
                            if (boundary == Boundary.DLR_HANDOFF_FAILED) {
                                last.completeExceptionally(new IllegalStateException("testDlrHandoff|error|reason:unavailable"));
                                if (!completion.toCompletableFuture().isCompletedExceptionally()) {
                                    throw new AssertionError("Failed handoff was not retained");
                                }
                            } else if (boundary == Boundary.TERMINAL_COMPLETE) {
                                last.complete(null);
                                completion.toCompletableFuture().join();
                            }
                            boolean retained = harness.pending.find(source("ordinary")).isPresent();
                            if (retained == (boundary == Boundary.TERMINAL_COMPLETE)) {
                                throw new AssertionError("Unexpected source completion at crash boundary");
                            }
                        }
                    }
                }
            }
            Files.writeString(root.resolve("ready"), boundary.name());
            new CountDownLatch(1).await();
        }
    }

    private static final class Harness implements AutoCloseable {
        private final FilePendingMessageStore pending;
        private final DefaultOutboundCoordinator<StandardMessage> coordinator;

        private Harness(Path root, Instant now) {
            Clock clock = Clock.fixed(now, ZoneOffset.UTC);
            pending = new FilePendingMessageStore(root.resolve("spool"), 16, clock);
            var router = new MemorySelectedRouterStore<>(pending, 8);
            var routed = new MemoryRoutedWorkStore<StandardMessage>(16, message -> PendingMessageData.capture(message).restore());
            coordinator = new DefaultOutboundCoordinator<>(pending, router, routed, 4, clock,
                    () -> Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).factory()));
        }

        @Override
        public void close() {
            coordinator.close();
        }
    }

    private static SourceId source(String name) {
        return new SourceId(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
    }

    private static StandardMessage message(String name, String body, String header) {
        var message = new StandardMessage();
        message.serial = source(name).value().toString();
        message.owner_id = "account";
        message.systemId = "system";
        message.from = "sender";
        message.to = "recipient";
        message.body = body;
        message.binheader = header;
        return message;
    }
}
