package gr.cytech.sendium.core.outbound.consumer;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.DefaultOutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.SelectedRouterStore;
import gr.cytech.sendium.core.storage.memory.MemoryPendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemoryRoutedWorkStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class OutboundEmbeddedLifecycleTest {
    @Test
    void customMessagesSurviveApplicationSelectionPolicyReturnsAndAsynchronousHandoffRetry() throws Exception {
        var pending = new MemoryPendingMessageStore<CustomMessage>(2, OutboundEmbeddedLifecycleTest::snapshot);
        var selected = new ApplicationSelectedStore(new MemorySelectedRouterStore<>(pending, 2));
        var routed = new MemoryRoutedWorkStore<CustomMessage>(2, OutboundEmbeddedLifecycleTest::snapshot);
        try (var coordinator = new DefaultOutboundCoordinator<>(pending, selected, routed)) {
            assertThat(selected.status().state()).isEqualTo(OutboundStage.State.NEW);
            coordinator.start();
            assertThat(selected.status().backend()).isEqualTo("application-selected");
            var firstSource = source();
            var secondSource = source();
            var first = message("custom-1");
            coordinator.accept(firstSource, first);
            coordinator.accept(secondSource, message("custom-2"));
            first.labels.add("caller mutation");

            // The application-owned port narrows the requested batch without a core configuration or CDI container.
            assertThat(coordinator.selectToRouter(10)).isEqualTo(1);
            assertThat(selected.requestedLimit).isEqualTo(10);
            var selection = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(selection.message().externalId).isEqualTo("custom-1");
            assertThat(selection.message().labels).containsExactly("accepted");
            coordinator.recordToRouted(selection, new Destination<>("application-provider", selection.message()));
            var work = coordinator.takeFromRouted("application-provider", Duration.ZERO).orElseThrow();
            work.message().labels.add("worker mutation");
            coordinator.returnToRouted(work);
            var returned = coordinator.takeFromRouted("application-provider", Duration.ZERO).orElseThrow();
            assertThat(returned.id()).isEqualTo(work.id());
            assertThat(returned.message().externalId).isEqualTo("custom-1");
            assertThat(returned.message().labels).containsExactly("accepted", "worker mutation");
            assertThat(pending.find(firstSource).orElseThrow().labels).containsExactly("accepted");

            var handoff = new CompletableFuture<Void>();
            var completion = coordinator.complete(returned.id(), handoff);
            assertThat(completion.toCompletableFuture()).isNotDone();
            handoff.completeExceptionally(new IllegalStateException("application handoff failed"));
            assertThatThrownBy(() -> completion.toCompletableFuture().join()).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(pending.find(firstSource)).isPresent();
            assertThat(coordinator.takeFromRouted("application-provider", Duration.ZERO)).isEmpty();
            coordinator.complete(returned.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
            assertThat(pending.find(firstSource)).isEmpty();
            assertThat(pending.find(secondSource).orElseThrow().externalId).isEqualTo("custom-2");

            // A late terminal callback cannot remove the other accepted source.
            coordinator.complete(returned.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
            assertThat(pending.find(secondSource)).isPresent();
            assertThat(coordinator.selectToRouter(10)).isEqualTo(1);
            var second = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(second.message().externalId).isEqualTo("custom-2");
            coordinator.discard(second.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
        }
        assertThat(selected.status().state()).isEqualTo(OutboundStage.State.CLOSED);
    }

    @Test
    void applicationOwnedShutdownReturnsTakenCustomWorkWithoutDeclaringItCompleted() throws Exception {
        var pending = spy(new MemoryPendingMessageStore<CustomMessage>(2, OutboundEmbeddedLifecycleTest::snapshot));
        var router = spy(new MemorySelectedRouterStore<>(pending, 2));
        var selected = new ApplicationSelectedStore(router);
        var routed = spy(new MemoryRoutedWorkStore<CustomMessage>(2, OutboundEmbeddedLifecycleTest::snapshot));
        try (var coordinator = new DefaultOutboundCoordinator<>(pending, selected, routed)) {
            assertThatThrownBy(() -> coordinator.accept(source(), message("before-start")))
                    .isInstanceOf(OutboundStorageException.class);
            coordinator.start();
            coordinator.accept(source(), message("routed"));
            coordinator.selectToRouter(1);
            var initial = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            coordinator.recordToRouted(initial, new Destination<>("custom-provider", initial.message()));
            var destinationTake = coordinator.takeFromRouted("custom-provider", Duration.ZERO).orElseThrow();
            coordinator.accept(source(), message("unassigned"));
            coordinator.selectToRouter(1);
            var routerTake = coordinator.takeForRouting(Duration.ZERO).orElseThrow();

            coordinator.beginShutdown();
            assertThatThrownBy(() -> coordinator.accept(source(), message("after-shutdown-start")))
                    .isInstanceOf(OutboundStorageException.class);
            coordinator.close();
            verify(router).release(routerTake);
            verify(routed).release(destinationTake);
            verify(pending, never()).complete(any());
            assertThat(pending.status().state()).isEqualTo(OutboundStage.State.CLOSED);
            assertThat(routed.status().state()).isEqualTo(OutboundStage.State.CLOSED);
        }
    }

    private static SourceId source() {
        return new SourceId(UUID.randomUUID());
    }

    private static CustomMessage message(String externalId) {
        var message = new CustomMessage();
        message.externalId = externalId;
        message.labels.add("accepted");
        return message;
    }

    private static CustomMessage snapshot(CustomMessage message) {
        try {
            var copy = (CustomMessage) message.clone();
            copy.labels = new ArrayList<>(message.labels);
            return copy;
        } catch (CloneNotSupportedException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class CustomMessage extends StandardMessage {
        private String externalId;
        private List<String> labels = new ArrayList<>();
    }

    /** Representative application implementation with its own selection bound and backend identity. */
    private static final class ApplicationSelectedStore implements SelectedRouterStore<CustomMessage> {
        private final SelectedRouterStore<CustomMessage> delegate;
        private int requestedLimit;

        private ApplicationSelectedStore(SelectedRouterStore<CustomMessage> delegate) {
            this.delegate = delegate;
        }

        @Override
        public int selectToRouter(int limit) {
            requestedLimit = limit;
            return delegate.selectToRouter(Math.min(limit, 1));
        }

        @Override
        public Optional<Selected<CustomMessage>> take(Duration timeout) throws InterruptedException {
            return delegate.take(timeout);
        }

        @Override
        public void release(Selected<CustomMessage> selected) {
            delegate.release(selected);
        }

        @Override
        public void markRouted(SelectionId selection) {
            delegate.markRouted(selection);
        }

        @Override
        public void complete(SelectionId selection) {
            delegate.complete(selection);
        }

        @Override
        public void open() {
            delegate.open();
        }

        @Override
        public Status status() {
            return new Status("application-selected", false, delegate.status().state());
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
