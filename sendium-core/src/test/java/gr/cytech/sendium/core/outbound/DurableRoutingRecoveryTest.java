package gr.cytech.sendium.core.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Assignment;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.outbound.OutboundWork.WorkId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.SelectedRouterStore;
import gr.cytech.sendium.core.storage.file.FilePendingMessageStore;
import gr.cytech.sendium.core.storage.file.FileRoutedWorkStore;
import gr.cytech.sendium.core.storage.file.FileSelectedRouterStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurableRoutingRecoveryTest {
    @TempDir Path root;

    @Test
    void memorySelectionExcludesRestoredRoutedSources() throws Exception {
        WorkId id = seed(false, false);
        var pending = new FilePendingMessageStore(root, 10);
        try (var coordinator = coordinator(pending, false)) {
            coordinator.start();
            var unrelated = message();
            coordinator.accept(source(unrelated), unrelated);
            assertThat(coordinator.selectToRouter(10)).isEqualTo(1);
            var selected = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(selected.sources()).containsExactly(source(unrelated));
            assertThat(coordinator.takeForRouting(Duration.ZERO)).isEmpty();
            var work = coordinator.takeFromRouted("provider-A", Duration.ZERO).orElseThrow();
            assertThat(work.id()).isEqualTo(id);
            coordinator.complete(id, CompletableFuture.completedFuture(null)).toCompletableFuture().join();
            assertThat(pending.sourcesAfter(null, 10)).containsExactly(source(unrelated));
            coordinator.discard(selected.id(), CompletableFuture.completedFuture(null)).toCompletableFuture().join();
        }
    }

    @Test
    void committedRoutedAssignmentOutranksSelectedScheduling() throws Exception {
        WorkId id = seed(true, false);
        var pending = new FilePendingMessageStore(root, 10);
        try (var coordinator = coordinator(pending, true)) {
            coordinator.start();
            assertThat(coordinator.selectToRouter(10)).isZero();
            assertThat(coordinator.takeForRouting(Duration.ZERO)).isEmpty();
            assertThat(coordinator.takeFromRouted("provider-B", Duration.ZERO)).isEmpty();
            var work = coordinator.takeFromRouted("provider-A", Duration.ZERO).orElseThrow();
            assertThat(work.id()).isEqualTo(id);
            assertThat(work.message().body).isEqualTo("routing snapshot");
            coordinator.complete(id, CompletableFuture.completedFuture(null)).toCompletableFuture().join();
            assertThat(pending.sourcesAfter(null, 1)).isEmpty();
        }
    }

    @Test
    void terminalRoutedFactResumesCleanupOnly() throws Exception {
        for (boolean selectedFile : new boolean[]{false, true}) {
            WorkId id = seed(selectedFile, true);
            var pending = new FilePendingMessageStore(root, 10);
            try (var coordinator = coordinator(pending, selectedFile)) {
                coordinator.start();
                assertThat(coordinator.isReady()).isTrue();
                assertThat(coordinator.takeForRouting(Duration.ZERO)).isEmpty();
                assertThat(coordinator.takeFromRouted("provider-A", Duration.ZERO)).isEmpty();
                assertThat(pending.sourcesAfter(null, 1)).isEmpty();
                coordinator.complete(id, CompletableFuture.completedFuture(null)).toCompletableFuture().join();
            }
        }
    }

    private WorkId seed(boolean selectedFile, boolean terminal) throws Exception {
        try (var pending = new FilePendingMessageStore(root, 10);
                var selected = new FileSelectedRouterStore(pending, 2);
                var routed = new FileRoutedWorkStore(pending, 10)) {
            pending.open();
            if (selectedFile) {
                selected.open();
            }
            routed.open();
            var message = message();
            pending.admit(source(message), message);
            SelectionId selection = new SelectionId(UUID.randomUUID());
            Set<SourceId> sources = Set.of(source(message));
            if (selectedFile) {
                selected.selectToRouter(1);
                var taken = selected.take(Duration.ZERO).orElseThrow();
                selection = taken.id();
                sources = taken.sources();
            }
            message.body = "routing snapshot";
            var work = routed.record(new Assignment<>(selection, sources, new Destination<>("provider-A", message)));
            if (terminal) {
                routed.take("provider-A", Duration.ZERO).orElseThrow();
                routed.complete(work.id());
            }
            return work.id();
        }
    }

    @Test
    void conflictingDurableOwnersFailBeforeScheduling() throws Exception {
        WorkId original = seed(false, false);
        try (var valid = coordinator(new FilePendingMessageStore(root, 10), false)) {
            valid.start();
            assertThat(valid.isReady()).isTrue();
        }
        var mapper = new ObjectMapper(new CBORFactory());
        Path originalPath = root.resolve("routed").resolve(original.value().toString().substring(0, 2)).resolve(original.value() + ".cbor");
        var node = (ObjectNode) mapper.readTree(Files.readAllBytes(originalPath));
        UUID conflicting = UUID.randomUUID();
        node.put("workId", conflicting.toString());
        node.put("selectionId", UUID.randomUUID().toString());
        Path conflictingPath = root.resolve("routed").resolve(conflicting.toString().substring(0, 2)).resolve(conflicting + ".cbor");
        Files.createDirectories(conflictingPath.getParent());
        Files.write(conflictingPath, mapper.writeValueAsBytes(node));
        var pending = new FilePendingMessageStore(root, 10);
        try (var coordinator = coordinator(pending, false)) {
            assertThatThrownBy(coordinator::start).isInstanceOf(OutboundStorageException.class);
            assertThat(coordinator.isReady()).isFalse();
            assertThat(Files.exists(originalPath)).isTrue();
            assertThat(Files.exists(conflictingPath)).isTrue();
        }
    }

    private DefaultOutboundCoordinator<StandardMessage> coordinator(FilePendingMessageStore pending, boolean selectedFile) {
        SelectedRouterStore<StandardMessage> selected = selectedFile ? new FileSelectedRouterStore(pending, 2) :
                new MemorySelectedRouterStore<>(pending, 2);
        return new DefaultOutboundCoordinator<>(pending, selected, new FileRoutedWorkStore(pending, 10));
    }

    private static StandardMessage message() {
        var message = new StandardMessage();
        message.serial = UUID.randomUUID().toString();
        message.body = "accepted snapshot";
        return message;
    }

    private static SourceId source(StandardMessage message) {
        return new SourceId(UUID.fromString(message.serial));
    }
}
