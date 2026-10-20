package gr.cytech.sendium.core.storage.pending;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.DefaultOutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.Routed;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.memory.MemoryPendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemoryRoutedWorkStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;
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

/** Faults wrap real memory stores; this fixture makes no persistence or process-restart claim. */
@Timeout(10)
class PendingCompletionFailureTest {
    @Test
    void uncertainAbsenceFailsCompletion() throws Exception {
        try (var fixture = new Fixture()) {
            var source = fixture.sources.iterator().next();
            var unavailable = new OutboundStorageException(OutboundStage.Role.PENDING, UNAVAILABLE, "Pending storage unavailable");
            doThrow(unavailable).when(fixture.pending).complete(fixture.sources);

            var result = fixture.coordinator.complete(fixture.work.id(), CompletableFuture.completedStage(null));

            assertThatThrownBy(() -> result.toCompletableFuture().join())
                    .isInstanceOf(CompletionException.class).hasCause(unavailable);
            assertThat(fixture.pending.find(source)).isPresent();
            verify(fixture.router, never()).complete(any());
            verify(fixture.routed, never()).forget(any());
            fixture.assertNotDispatchable();

            doCallRealMethod().when(fixture.pending).complete(fixture.sources);
            fixture.coordinator.complete(fixture.work.id(), new CompletableFuture<>()).toCompletableFuture().join();
            fixture.sources.forEach(id -> assertThat(fixture.pending.find(id)).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resumesPartialSourceSetCompletion(boolean removeAllBeforeFailure) throws Exception {
        try (var fixture = new Fixture()) {
            SourceId first = fixture.sources.iterator().next();
            doAnswer(invocation -> {
                // Model an interrupted batch or a committed delete whose acknowledgement was lost.
                Set<SourceId> removed = removeAllBeforeFailure ? fixture.sources : Set.of(first);
                doCallRealMethod().when(fixture.pending).complete(removed);
                fixture.pending.complete(removed);
                throw new OutboundStorageException(OutboundStage.Role.PENDING, UNAVAILABLE, "Completion result unavailable");
            }).when(fixture.pending).complete(fixture.sources);

            var result = fixture.coordinator.complete(fixture.work.id(), CompletableFuture.completedStage(null));
            assertThat(result.toCompletableFuture()).isCompletedExceptionally();
            assertThat(fixture.pending.find(first)).isEmpty();
            long retained = fixture.sources.stream().filter(id -> fixture.pending.find(id).isPresent()).count();
            assertThat(retained).isEqualTo(removeAllBeforeFailure ? 0 : fixture.sources.size() - 1);
            verify(fixture.router, never()).complete(any());
            verify(fixture.routed, never()).forget(any());
            fixture.assertNotDispatchable();

            // Even apparent absence does not let the coordinator bypass a failed completion boundary.
            var unavailable = new OutboundStorageException(OutboundStage.Role.PENDING, UNAVAILABLE, "Pending storage unavailable");
            doThrow(unavailable).when(fixture.pending).complete(fixture.sources);
            var retry = fixture.coordinator.complete(fixture.work.id(), new CompletableFuture<>());
            assertThatThrownBy(() -> retry.toCompletableFuture().join()).hasCause(unavailable);
            verify(fixture.router, never()).complete(any());
            fixture.assertNotDispatchable();

            doCallRealMethod().when(fixture.pending).complete(fixture.sources);
            fixture.coordinator.complete(fixture.work.id(), new CompletableFuture<>()).toCompletableFuture().join();
            fixture.sources.forEach(id -> assertThat(fixture.pending.find(id)).isEmpty());
            verify(fixture.routed, times(1)).complete(fixture.work.id());
            verify(fixture.router, times(1)).complete(fixture.selected.id());
            verify(fixture.routed, times(1)).forget(fixture.selected.id());
            verify(fixture.routed, times(1)).record(any());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final MemoryPendingMessageStore<StandardMessage> pending = spy(new MemoryPendingMessageStore<>(3, Fixture::copy));
        private final MemorySelectedRouterStore<StandardMessage> router = spy(new MemorySelectedRouterStore<>(pending, 1));
        private final MemoryRoutedWorkStore<StandardMessage> routed = spy(new MemoryRoutedWorkStore<>(1, Fixture::copy));
        private final DefaultOutboundCoordinator<StandardMessage> coordinator = new DefaultOutboundCoordinator<>(pending, router, routed);
        private final Set<SourceId> sources = new LinkedHashSet<>();
        private final Selected<StandardMessage> selected;
        private final Routed<StandardMessage> work;

        private Fixture() throws InterruptedException {
            coordinator.start();
            for (int part = 1; part <= 3; part++) {
                SourceId id = new SourceId(UUID.randomUUID());
                StandardMessage message = new StandardMessage();
                message.serial = id.value().toString();
                message.body = "part-" + part;
                sources.add(coordinator.acceptHeld(id, message));
            }
            StandardMessage aggregate = new StandardMessage();
            aggregate.body = "aggregate";
            coordinator.makeHeldReady(sources, aggregate);
            assertThat(coordinator.selectToRouter(1)).isEqualTo(1);
            selected = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            coordinator.recordToRouted(selected, new Destination<>("provider", selected.message()));
            work = coordinator.takeFromRouted("provider", Duration.ZERO).orElseThrow();
        }

        private void assertNotDispatchable() throws InterruptedException {
            assertThat(coordinator.selectToRouter(1)).isZero();
            assertThat(coordinator.takeForRouting(Duration.ZERO)).isEmpty();
            assertThat(coordinator.takeFromRouted("provider", Duration.ZERO)).isEmpty();
        }

        private static StandardMessage copy(StandardMessage message) {
            try {
                return (StandardMessage) message.clone();
            } catch (CloneNotSupportedException failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        public void close() {
            // Let failed assertions report their cause instead of leaving an unretryable test-owned cleanup behind.
            doCallRealMethod().when(pending).complete(sources);
            coordinator.complete(work.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
            coordinator.close();
        }
    }
}
