package gr.cytech.sendium.core.outbound;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.outbound.OutboundWork.WorkId;
import gr.cytech.sendium.core.storage.PendingMessageStore;
import gr.cytech.sendium.core.storage.RoutedWorkStore;
import gr.cytech.sendium.core.storage.SelectedRouterStore;
import gr.cytech.sendium.core.storage.file.FilePendingMessageStore;
import gr.cytech.sendium.core.storage.file.FileRoutedWorkStore;
import gr.cytech.sendium.core.storage.file.FileSelectedRouterStore;
import gr.cytech.sendium.core.storage.memory.MemoryPendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemoryRoutedWorkStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** In-process failure conformance; physical crash/restart guarantees stay in backend tests. */
@Timeout(15)
class StageTransitionContractTest {
    @TempDir Path root;

    static Stream<String> profiles() {
        return Stream.of("memory/memory/memory", "file/memory/memory", "file/file/memory",
                "file/memory/file", "file/file/file");
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void recordFailureRetainsAcceptedSource(String profile) throws Exception {
        try (var fixture = new Fixture(profile, root)) {
            var selected = fixture.select();
            doThrow(new IllegalStateException("injected record failure")).doCallRealMethod()
                    .when(fixture.routed).record(any());
            assertThatThrownBy(() -> fixture.coordinator.recordToRouted(selected, destination(selected)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(fixture.pending.find(fixture.source)).isPresent();
            assertThat(fixture.coordinator.selectToRouter(1)).isZero();
            verify(fixture.pending, never()).complete(any());
            var recorded = fixture.coordinator.recordToRouted(selected, destination(selected));
            var taken = fixture.coordinator.takeFromRouted("A", Duration.ZERO).orElseThrow();
            assertThat(taken.id()).isEqualTo(recorded.id());
            fixture.coordinator.complete(taken.id(), CompletableFuture.completedFuture(null)).toCompletableFuture().join();
            assertThat(fixture.pending.find(fixture.source)).isEmpty();
        }
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void publishedAssignmentSurvivesMarkFailure(String profile) throws Exception {
        try (var fixture = new Fixture(profile, root)) {
            var selected = fixture.select();
            var published = new AtomicReference<WorkId>();
            doAnswer(invocation -> {
                var recorded = (OutboundWork.Routed<?>) invocation.callRealMethod();
                published.compareAndSet(null, recorded.id());
                return recorded;
            }).when(fixture.routed).record(any());
            doThrow(new IllegalStateException("injected mark failure")).when(fixture.selected).markRouted(selected.id());
            assertThatThrownBy(() -> fixture.coordinator.recordToRouted(selected, destination(selected)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(published.get()).isNotNull();
            assertThat(fixture.pending.find(fixture.source)).isPresent();
            doCallRealMethod().when(fixture.selected).markRouted(selected.id());
            var reconciled = fixture.coordinator.recordToRouted(selected, destination(selected));
            assertThat(reconciled.id()).isEqualTo(published.get());
            var taken = fixture.coordinator.takeFromRouted("A", Duration.ZERO).orElseThrow();
            assertThat(taken.id()).isEqualTo(published.get());
            assertThat(fixture.coordinator.takeForRouting(Duration.ZERO)).isEmpty();
            fixture.coordinator.complete(taken.id(), CompletableFuture.completedFuture(null)).toCompletableFuture().join();
        }
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void handoffFailureRetainsAcceptedSource(String profile) throws Exception {
        try (var fixture = new Fixture(profile, root)) {
            var selected = fixture.select();
            fixture.coordinator.recordToRouted(selected, destination(selected));
            var taken = fixture.coordinator.takeFromRouted("A", Duration.ZERO).orElseThrow();
            var failedHandoff = CompletableFuture.<Void>failedFuture(new IllegalStateException("injected handoff failure"));
            var completion = fixture.coordinator.complete(taken.id(), failedHandoff).toCompletableFuture();
            assertThat(completion).isCompletedExceptionally();
            assertThat(fixture.pending.find(fixture.source)).isPresent();
            verify(fixture.routed, never()).complete(any());
            assertThat(fixture.coordinator.takeFromRouted("A", Duration.ZERO)).isEmpty();
            fixture.coordinator.complete(taken.id(), CompletableFuture.completedFuture(null)).toCompletableFuture().join();
            assertThat(fixture.pending.find(fixture.source)).isEmpty();
            verify(fixture.routed, times(1)).record(any());
        }
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void cleanupRetryDoesNotRescheduleProviderWork(String profile) throws Exception {
        try (var fixture = new Fixture(profile, root)) {
            var selected = fixture.select();
            fixture.coordinator.recordToRouted(selected, destination(selected));
            var taken = fixture.coordinator.takeFromRouted("A", Duration.ZERO).orElseThrow();
            doThrow(new IllegalStateException("injected cleanup failure")).doCallRealMethod()
                    .when(fixture.pending).complete(selected.sources());
            var completion = fixture.coordinator.complete(taken.id(), CompletableFuture.completedFuture(null)).toCompletableFuture();
            assertThat(completion).isCompletedExceptionally();
            assertThat(fixture.pending.find(fixture.source)).isPresent();
            verify(fixture.routed, times(1)).complete(taken.id());
            assertThat(fixture.coordinator.takeFromRouted("A", Duration.ZERO)).isEmpty();
            assertThat(fixture.coordinator.selectToRouter(1)).isZero();
            assertThat(fixture.coordinator.takeForRouting(Duration.ZERO)).isEmpty();
            // The handoff already succeeded; cleanup retry must not await another handoff.
            fixture.coordinator.complete(taken.id(), new CompletableFuture<>()).toCompletableFuture().join();
            assertThat(fixture.pending.find(fixture.source)).isEmpty();
            verify(fixture.routed, times(1)).record(any());
            verify(fixture.routed, times(1)).complete(taken.id());
            verify(fixture.routed, never()).release(any());
        }
    }

    private static Destination<StandardMessage> destination(Selected<StandardMessage> selected) {
        return new Destination<>("A", selected.message());
    }

    private static StandardMessage snapshot(StandardMessage message) {
        return PendingMessageData.capture(message).restore();
    }

    private static final class Fixture implements AutoCloseable {
        private final PendingMessageStore<StandardMessage> pending;
        private final SelectedRouterStore<StandardMessage> selected;
        private final RoutedWorkStore<StandardMessage> routed;
        private final DefaultOutboundCoordinator<StandardMessage> coordinator;
        private final SourceId source = new SourceId(UUID.randomUUID());

        private Fixture(String profile, Path root) {
            String[] backends = profile.split("/");
            if ("file".equals(backends[0])) {
                var filePending = spy(new FilePendingMessageStore(root, 2));
                pending = filePending;
                selected = "file".equals(backends[1]) ? spy(new FileSelectedRouterStore(filePending, 1)) :
                        spy(new MemorySelectedRouterStore<>(filePending, 1));
                routed = "file".equals(backends[2]) ? spy(new FileRoutedWorkStore(filePending, 2)) :
                        spy(new MemoryRoutedWorkStore<>(2, StageTransitionContractTest::snapshot));
            } else {
                var memoryPending = spy(new MemoryPendingMessageStore<>(2, StageTransitionContractTest::snapshot));
                pending = memoryPending;
                selected = spy(new MemorySelectedRouterStore<>(memoryPending, 1));
                routed = spy(new MemoryRoutedWorkStore<>(2, StageTransitionContractTest::snapshot));
            }
            coordinator = new DefaultOutboundCoordinator<>(pending, selected, routed);
            coordinator.start();
        }

        private Selected<StandardMessage> select() throws InterruptedException {
            var message = new StandardMessage();
            message.serial = source.value().toString();
            message.priority = 2;
            message.body = "synthetic";
            coordinator.accept(source, message);
            assertThat(coordinator.selectToRouter(1)).isEqualTo(1);
            return coordinator.takeForRouting(Duration.ZERO).orElseThrow();
        }

        @Override
        public void close() {
            coordinator.close();
        }
    }
}
