package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.DefaultOutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundWork.Assignment;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.RoutedWorkStore;
import gr.cytech.sendium.core.storage.SelectedRouterStore;
import gr.cytech.sendium.core.storage.memory.MemoryRoutedWorkStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(180)
class FileStageFaultTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {"selected", "routed"})
    void publicationFailureRetainsAcceptedOwnership(String stage) throws Exception {
        SourceId source;
        try (var pending = new FilePendingMessageStore(root, 10);
                var selected = new FileSelectedRouterStore(pending, 2);
                var routed = new FileRoutedWorkStore(pending, 10)) {
            pending.open();
            selected.open();
            routed.open();
            var message = message();
            source = new SourceId(UUID.fromString(message.serial));
            pending.admit(source, message);
            if (stage.equals("selected")) {
                Files.delete(root.resolve("selected"));
                Files.writeString(root.resolve("selected"), "injected publication obstruction");
                assertThatThrownBy(() -> selected.selectToRouter(1)).isInstanceOf(OutboundStorageException.class);
                assertThat(selected.status().state()).isEqualTo(OutboundStage.State.FAILED);
            } else {
                selected.selectToRouter(1);
                var take = selected.take(Duration.ZERO).orElseThrow();
                Files.delete(root.resolve("routed"));
                Files.writeString(root.resolve("routed"), "injected publication obstruction");
                assertThatThrownBy(() -> routed.record(new Assignment<>(
                        take.id(), take.sources(), new Destination<>("provider", take.message()))))
                        .isInstanceOf(OutboundStorageException.class);
                assertThat(routed.status().state()).isEqualTo(OutboundStage.State.FAILED);
            }
            Path accepted = root.resolve("accepted/2").resolve(source.value().toString().substring(0, 2)).resolve(source.value() + ".cbor");
            assertThat(Files.size(accepted)).isPositive();
        }
        // Remove only the injected obstruction, never an authoritative message file.
        Files.delete(root.resolve(stage));
        Files.createDirectory(root.resolve(stage));
        try (var pending = new FilePendingMessageStore(root, 10);
                var selected = new FileSelectedRouterStore(pending, 2)) {
            pending.open();
            selected.open();
            assertThat(pending.find(source)).isPresent();
            selected.selectToRouter(1);
            assertThat(selected.take(Duration.ZERO).orElseThrow().sources()).containsExactly(source);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"file/file/memory", "file/memory/file", "file/file/file"})
    void reducedCapacityRetainsExcessDurableBacklog(String profile) throws Exception {
        try (var original = new Harness(root, profile, 200, 140)) {
            original.coordinator.start();
            for (int i = 0; i < 140; i++) {
                var message = message();
                original.coordinator.accept(new SourceId(UUID.fromString(message.serial)), message);
            }
            assertThat(original.coordinator.selectToRouter(140)).isEqualTo(140);
            if (profile.endsWith("file")) {
                for (int i = 0; i < 140; i++) {
                    var selected = original.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
                    original.coordinator.recordToRouted(selected, new Destination<>("provider", selected.message()));
                }
            }
        }
        try (var recovered = new Harness(root, profile, 2, 2)) {
            recovered.coordinator.start();
            String namespace = profile.endsWith("file") ? "routed" : "selected";
            try (var files = Files.walk(root.resolve(namespace))) {
                assertThat(files.filter(path -> path.toString().endsWith(".cbor")).count()).isEqualTo(140);
            }
            assertWindow(recovered.selected, 2);
            assertWindow(recovered.routed, 2);
            var completed = new HashSet<UUID>();
            for (int i = 0; i < 140; i++) {
                if (profile.endsWith("memory")) {
                    var selected = recovered.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
                    recovered.coordinator.recordToRouted(selected, new Destination<>("provider", selected.message()));
                }
                var work = recovered.coordinator.takeFromRouted("provider", Duration.ZERO).orElseThrow();
                assertThat(completed.add(UUID.fromString(work.message().serial))).isTrue();
                assertWindow(recovered.selected, 2);
                assertWindow(recovered.routed, 2);
                recovered.coordinator.complete(work.id(), CompletableFuture.completedFuture(null)).toCompletableFuture().join();
            }
            assertThat(completed).hasSize(140);
            assertThat(recovered.pending.sourcesAfter(null, 1)).isEmpty();
            assertThat(recovered.coordinator.takeForRouting(Duration.ZERO)).isEmpty();
            assertThat(recovered.coordinator.takeFromRouted("provider", Duration.ZERO)).isEmpty();
        }
    }

    private static void assertWindow(Object store, int limit) throws Exception {
        if (store instanceof FileSelectedRouterStore || store instanceof FileRoutedWorkStore) {
            var field = store.getClass().getDeclaredField("active");
            field.setAccessible(true);
            assertThat(((Map<?, ?>) field.get(store)).size()).isLessThanOrEqualTo(limit);
        }
    }

    private static StandardMessage message() {
        var message = new StandardMessage();
        message.serial = UUID.randomUUID().toString();
        message.body = "retained stage payload";
        return message;
    }

    private static final class Harness implements AutoCloseable {
        private final FilePendingMessageStore pending;
        private final SelectedRouterStore<StandardMessage> selected;
        private final RoutedWorkStore<StandardMessage> routed;
        private final DefaultOutboundCoordinator<StandardMessage> coordinator;

        private Harness(Path path, String profile, int capacity, int window) {
            pending = new FilePendingMessageStore(path, capacity);
            selected = profile.startsWith("file/file/") ? new FileSelectedRouterStore(pending, window) :
                    new MemorySelectedRouterStore<>(pending, window);
            routed = profile.endsWith("file") ? new FileRoutedWorkStore(pending, capacity, window) :
                    new MemoryRoutedWorkStore<>(capacity, value -> PendingMessageData.capture(value).restore());
            coordinator = new DefaultOutboundCoordinator<>(pending, selected, routed, 2);
        }

        @Override
        public void close() {
            coordinator.close();
        }
    }
}
