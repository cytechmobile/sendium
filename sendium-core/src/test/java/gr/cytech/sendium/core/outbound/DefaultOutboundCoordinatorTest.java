package gr.cytech.sendium.core.outbound;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Completed;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.Routed;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.memory.MemoryPendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemoryRoutedWorkStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.CAPACITY_EXCEEDED;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.INVALID_TRANSITION;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNSUPPORTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Timeout(10)
class DefaultOutboundCoordinatorTest {
    @Test
    void constructionIsPassiveAndStartupOwnsStageOrdering() {
        try (var h = new Harness()) {
            verifyNoInteractions(h.pending, h.router, h.routed);
            fails(() -> h.coordinator.accept(source(), new Payload("one")), UNAVAILABLE);
            h.start();
            h.coordinator.start();
            var order = inOrder(h.pending, h.router, h.routed);
            order.verify(h.pending).open();
            order.verify(h.router).open();
            order.verify(h.routed).open();
            h.coordinator.beginShutdown();
            fails(h.coordinator::start, INVALID_TRANSITION);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedStartupClosesEveryAttemptedStageAndPreservesCleanupErrors(boolean error) {
        try (var h = new Harness()) {
            Throwable opening = error ? new AssertionError("open failed") : new IllegalStateException("open failed");
            var closing = new IllegalStateException("close failed");
            doThrow(opening).when(h.routed).open();
            doThrow(closing).when(h.router).close();
            assertThatThrownBy(h.coordinator::start).isSameAs(opening);
            assertThat(opening.getSuppressed()).containsExactly(closing);
            var order = inOrder(h.routed, h.router, h.pending);
            order.verify(h.routed).close();
            order.verify(h.router).close();
            order.verify(h.pending).close();
            fails(h.coordinator::start, INVALID_TRANSITION);
        }
    }

    @Test
    void durableOrAlreadyOpenedStagesAreNotSilentlyAdopted() {
        try (var h = new Harness()) {
            when(h.routed.status()).thenReturn(new OutboundStage.Status("file", true, OutboundStage.State.NEW));
            fails(h.coordinator::start, UNSUPPORTED);
            verify(h.routed, never()).open();
            verify(h.routed, never()).close();
            verify(h.router).close();
            verify(h.pending).close();
        }
        try (var h = new Harness()) {
            h.pending.open();
            fails(h.coordinator::start, INVALID_TRANSITION);
            verify(h.pending, never()).close();
            h.pending.close();
        }
    }

    @Test
    void successfulHandoffCleansUpInOrderAndLateCompletionCannotRecreateWork() throws Exception {
        try (var h = new Harness().start()) {
            var delivery = h.delivery();
            h.coordinator.complete(new OutboundWork.WorkId(UUID.randomUUID()), new CompletableFuture<>())
                    .toCompletableFuture().join();
            assertThat(h.pending.find(delivery.source())).isPresent();
            CompletableFuture<Void> handoff = new CompletableFuture<>();
            CompletionStage<Void> result = h.coordinator.complete(delivery.work.id(), handoff);
            assertThat(result.toCompletableFuture()).isNotDone();
            assertThat(h.pending.find(delivery.source())).isPresent();
            assertThat((Object) h.coordinator.complete(delivery.work.id(), CompletableFuture.completedStage(null))).isSameAs(result);
            fails(() -> h.coordinator.returnToRouted(delivery.work), INVALID_TRANSITION);
            handoff.complete(null);
            result.toCompletableFuture().join();
            var order = inOrder(h.routed, h.pending, h.router);
            order.verify(h.routed).complete(delivery.work.id());
            order.verify(h.pending).complete(delivery.selected.sources());
            order.verify(h.router).complete(delivery.selected.id());
            order.verify(h.routed).forget(delivery.selected.id());
            assertThat(h.pending.find(delivery.source())).isEmpty();
            h.coordinator.complete(delivery.work.id(), new CompletableFuture<>()).toCompletableFuture().join();
            verify(h.routed, times(1)).complete(delivery.work.id());
            fails(() -> h.coordinator.recordToRouted(delivery.selected, destination("A", "stale")), INVALID_TRANSITION);
            fails(() -> h.coordinator.returnToRouted(delivery.work), INVALID_TRANSITION);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedOrCancelledHandoffRetainsOwnershipForHandoffOnlyRetry(boolean cancel) throws Exception {
        try (var h = new Harness().start()) {
            var delivery = h.delivery();
            var handoff = new CompletableFuture<Void>();
            var result = h.coordinator.complete(delivery.work.id(), handoff);
            if (cancel) {
                handoff.cancel(false);
            } else {
                handoff.completeExceptionally(new IllegalStateException("handoff failed"));
            }
            assertThat(result.toCompletableFuture()).isCompletedExceptionally();
            assertThat(h.pending.find(delivery.source())).isPresent();
            verify(h.routed, never()).complete(any());
            assertThat(h.coordinator.takeFromRouted("A", Duration.ZERO)).isEmpty();
            fails(() -> h.coordinator.returnToRouted(delivery.work), INVALID_TRANSITION);
            h.coordinator.complete(delivery.work.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
            assertThat(h.pending.find(delivery.source())).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"routed", "pending", "selected", "forget"})
    void cleanupFailureCanBeRetriedWithoutAnotherHandoffOrDispatch(String boundary) throws Exception {
        try (var h = new Harness().start()) {
            var delivery = h.delivery();
            var failure = new IllegalStateException("cleanup failed");
            switch (boundary) {
                case "routed" -> doThrow(failure).when(h.routed).complete(delivery.work.id());
                case "pending" -> doThrow(failure).when(h.pending).complete(delivery.selected.sources());
                case "selected" -> doThrow(failure).when(h.router).complete(delivery.selected.id());
                case "forget" -> doThrow(failure).when(h.routed).forget(delivery.selected.id());
                default -> throw new AssertionError(boundary);
            }
            var result = h.coordinator.complete(delivery.work.id(), CompletableFuture.completedStage(null));
            assertThat(result.toCompletableFuture()).isCompletedExceptionally();
            fails(h.coordinator::close, UNAVAILABLE);
            doCallRealMethod().when(h.routed).complete(delivery.work.id());
            doCallRealMethod().when(h.pending).complete(delivery.selected.sources());
            doCallRealMethod().when(h.router).complete(delivery.selected.id());
            doCallRealMethod().when(h.routed).forget(delivery.selected.id());
            h.coordinator.complete(delivery.work.id(), new CompletableFuture<>()).toCompletableFuture().join();
            assertThat(h.pending.find(delivery.source())).isEmpty();
            verify(h.routed, never()).release(any());
        }
    }

    @Test
    void cleanupErrorsSettleTheCompletionStageRatherThanLeavingItPending() throws Exception {
        try (var h = new Harness().start()) {
            var delivery = h.delivery();
            doThrow(new AssertionError("cleanup error")).when(h.pending).complete(delivery.selected.sources());
            var result = h.coordinator.complete(delivery.work.id(), CompletableFuture.completedStage(null));
            assertThat(result.toCompletableFuture()).isCompletedExceptionally();
            assertThat(h.pending.find(delivery.source())).isPresent();
            doCallRealMethod().when(h.pending).complete(delivery.selected.sources());
            h.coordinator.complete(delivery.work.id(), new CompletableFuture<>()).toCompletableFuture().join();
        }
    }

    @Test
    void completionResultCannotRemoveUnrelatedSources() throws Exception {
        try (var h = new Harness().start()) {
            var delivery = h.delivery();
            var unrelated = source();
            h.coordinator.accept(unrelated, new Payload("unrelated"));
            doReturn(new Completed(delivery.selected.id(), Set.of(unrelated)))
                    .when(h.routed).complete(delivery.work.id());
            var completion = h.coordinator.complete(delivery.work.id(), CompletableFuture.completedStage(null));
            assertThat(completion.toCompletableFuture()).isCompletedExceptionally();
            verify(h.pending, never()).complete(any());
            assertThat(h.pending.find(unrelated)).isPresent();
            assertThat(h.pending.find(delivery.source())).isPresent();
            doCallRealMethod().when(h.routed).complete(delivery.work.id());
            h.coordinator.complete(delivery.work.id(), new CompletableFuture<>()).toCompletableFuture().join();
            assertThat(h.pending.find(unrelated)).isPresent();
        }
    }

    @Test
    void returnedFutureCannotCancelOwnedCleanupAndCallbacksRunOutsideTheLock() throws Exception {
        try (var h = new Harness().start(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var delivery = h.delivery();
            var handoff = new CompletableFuture<Void>();
            var completion = h.coordinator.complete(delivery.work.id(), handoff);
            completion.toCompletableFuture().cancel(false);
            var nextSource = source();
            var callback = completion.thenRun(() -> {
                try {
                    executor.submit(() -> h.coordinator.accept(nextSource, new Payload("next"))).get(1, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            handoff.complete(null);
            callback.toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertThat(h.pending.find(delivery.source())).isEmpty();
            assertThat(h.pending.find(nextSource)).isPresent();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"record", "mark"})
    void routingRetryReconcilesPublishedDestinationWithoutRoutingAgain(String boundary) throws Exception {
        try (var h = new Harness().start()) {
            var selected = h.select("one");
            if (boundary.equals("record")) {
                AtomicBoolean first = new AtomicBoolean(true);
                doAnswer(call -> {
                    Object result = call.callRealMethod();
                    if (first.getAndSet(false)) {
                        throw new IllegalStateException("record acknowledged late");
                    }
                    return result;
                }).when(h.routed).record(any());
            } else {
                doThrow(new IllegalStateException("mark failed")).when(h.router).markRouted(selected.id());
            }
            assertThatThrownBy(() -> h.coordinator.recordToRouted(selected, destination("A", "chosen")))
                    .isInstanceOf(IllegalStateException.class);
            fails(() -> h.coordinator.requeueForRouting(selected), INVALID_TRANSITION);
            fails(() -> h.coordinator.recordToRouted(selected, destination("B", "different")), INVALID_TRANSITION);
            fails(() -> h.coordinator.takeFromRouted("A", Duration.ZERO), UNAVAILABLE);
            doCallRealMethod().when(h.router).markRouted(selected.id());
            var receipt = h.coordinator.recordToRouted(selected, destination("A", "retry payload"));
            var taken = h.coordinator.takeFromRouted("A", Duration.ZERO).orElseThrow();
            assertThat(taken.id()).isEqualTo(receipt.id());
            assertThat(taken.message().body).isEqualTo("chosen");
            h.coordinator.complete(taken.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
        }
    }

    @Test
    void destinationCapacityBackpressureDoesNotPreventExistingWorkFromCompleting() throws Exception {
        try (var h = new Harness(1).start()) {
            var first = h.select("first");
            h.coordinator.recordToRouted(first, destination("A", "first"));
            var second = h.select("second");
            fails(() -> h.coordinator.recordToRouted(second, destination("A", "second")), CAPACITY_EXCEEDED);
            var taken = h.coordinator.takeFromRouted("A", Duration.ZERO).orElseThrow();
            assertThat(taken.selection()).isEqualTo(first.id());
            h.coordinator.complete(taken.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
            h.coordinator.recordToRouted(second, destination("A", "retry"));
            taken = h.coordinator.takeFromRouted("A", Duration.ZERO).orElseThrow();
            assertThat(taken.message().body).isEqualTo("second");
            h.coordinator.complete(taken.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
        }
    }

    @Test
    void queuedReceiptsCannotCompleteBeforeTheyAreTaken() throws Exception {
        try (var h = new Harness().start()) {
            var selected = h.select("one");
            var receipt = h.coordinator.recordToRouted(selected, destination("A", "one"));
            fails(() -> h.coordinator.complete(receipt.id(), CompletableFuture.completedStage(null)), INVALID_TRANSITION);
            var taken = h.coordinator.takeFromRouted("A", Duration.ZERO).orElseThrow();
            h.coordinator.complete(taken.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
        }
    }

    @Test
    void unexposedTakeSurvivesFailureWhileReturningAnUnfinishedRoute() throws Exception {
        try (var h = new Harness().start()) {
            var selected = h.select("one");
            doThrow(new IllegalStateException("mark failed")).when(h.router).markRouted(selected.id());
            assertThatThrownBy(() -> h.coordinator.recordToRouted(selected, destination("A", "one"))).isInstanceOf(IllegalStateException.class);
            doThrow(new IllegalStateException("return failed")).when(h.routed).release(any());
            assertThatThrownBy(() -> h.coordinator.takeFromRouted("A", Duration.ZERO)).isInstanceOf(IllegalStateException.class);
            doCallRealMethod().when(h.router).markRouted(selected.id());
            doCallRealMethod().when(h.routed).release(any());
            h.coordinator.recordToRouted(selected, destination("A", "one"));
            var taken = h.coordinator.takeFromRouted("A", Duration.ZERO).orElseThrow();
            h.coordinator.complete(taken.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
        }
    }

    @Test
    void routingRequeueAndRoutedReturnPreserveMutationsAndRejectForgedTakes() throws Exception {
        try (var h = new Harness().start()) {
            var selected = h.select("original");
            selected.message().body = "filtered";
            h.coordinator.requeueForRouting(selected);
            var next = h.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            h.coordinator.requeueForRouting(selected);
            assertThat(next.message().body).isEqualTo("filtered");
            assertThat(h.coordinator.takeForRouting(Duration.ZERO)).isEmpty();
            fails(() -> h.coordinator.recordToRouted(new Selected<>(next.id(), next.sources(), next.message()), destination("A", "fake")),
                    INVALID_TRANSITION);
            h.coordinator.recordToRouted(next, new Destination<>("A", next.message()));
            var first = h.coordinator.takeFromRouted("A", Duration.ZERO).orElseThrow();
            first.message().body = "retry";
            h.coordinator.returnToRouted(first);
            var second = h.coordinator.takeFromRouted("A", Duration.ZERO).orElseThrow();
            h.coordinator.returnToRouted(first);
            assertThat(second.message().body).isEqualTo("retry");
            assertThat(h.coordinator.takeFromRouted("A", Duration.ZERO)).isEmpty();
            h.coordinator.complete(second.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
        }
    }

    @Test
    void discardWaitsForHandoffAndCannotBeUsedAfterAssignment() throws Exception {
        try (var h = new Harness().start()) {
            var selected = h.select("drop");
            var handoff = new CompletableFuture<Void>();
            var result = h.coordinator.discard(selected.id(), handoff);
            assertThat(h.pending.find(selected.sources().iterator().next())).isPresent();
            fails(() -> h.coordinator.requeueForRouting(selected), INVALID_TRANSITION);
            handoff.completeExceptionally(new IllegalStateException("handoff failed"));
            assertThat(result.toCompletableFuture()).isCompletedExceptionally();
            h.coordinator.discard(selected.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
            h.coordinator.discard(selected.id(), new CompletableFuture<>()).toCompletableFuture().join();
            verify(h.routed, never()).record(any());
            var delivery = h.delivery();
            fails(() -> h.coordinator.discard(delivery.selected.id(), CompletableFuture.completedStage(null)), INVALID_TRANSITION);
            h.coordinator.complete(delivery.work.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
        }
    }

    @Test
    void multiSourceCleanupCanRetryAfterPartialRemoval() throws Exception {
        var pending = spy(new MemoryPendingMessageStore<Payload>(2, Payload::copy));
        var router = spy(new MemorySelectedRouterStore<>(pending, 1));
        var routed = spy(new MemoryRoutedWorkStore<Payload>(1, Payload::copy));
        var sources = Set.of(source(), source());
        try (var coordinator = new DefaultOutboundCoordinator<>(pending, router, routed)) {
            coordinator.start();
            sources.forEach(source -> coordinator.acceptHeld(source, new Payload("part")));
            coordinator.makeHeldReady(sources, new Payload("aggregate"));
            coordinator.selectToRouter(1);
            var selected = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(selected.sources()).isEqualTo(sources);
            coordinator.recordToRouted(selected, destination("A", "aggregate"));
            var taken = coordinator.takeFromRouted("A", Duration.ZERO).orElseThrow();
            AtomicBoolean first = new AtomicBoolean(true);
            doAnswer(call -> {
                if (first.getAndSet(false)) {
                    pending.complete(Set.of(sources.iterator().next()));
                    throw new IllegalStateException("partial source completion");
                }
                return call.callRealMethod();
            }).when(pending).complete(sources);
            var result = coordinator.complete(taken.id(), CompletableFuture.completedStage(null));
            assertThat(result.toCompletableFuture()).isCompletedExceptionally();
            assertThat(sources.stream().filter(source -> pending.find(source).isPresent()).count()).isEqualTo(1);
            sources.forEach(source -> coordinator.accept(source, new Payload("duplicate during cleanup")));
            sources.forEach(source -> coordinator.acceptHeld(source, new Payload("held duplicate during cleanup")));
            assertThat(sources.stream().filter(source -> pending.find(source).isPresent()).count()).isEqualTo(1);
            fails(() -> coordinator.makeHeldReady(sources, new Payload("late publication")), INVALID_TRANSITION);
            coordinator.complete(taken.id(), new CompletableFuture<>()).toCompletableFuture().join();
            sources.forEach(source -> assertThat(pending.find(source)).isEmpty());
            verify(routed, times(1)).complete(taken.id());
            verify(pending, times(2)).complete(sources);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void beginningShutdownWakesWaitingTakesAndKeepsCompletionAvailable(boolean destination) throws Exception {
        try (var h = new Harness().start(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var delivery = h.delivery();
            AtomicReference<Thread> thread = new AtomicReference<>();
            var waiting = executor.submit(() -> {
                thread.set(Thread.currentThread());
                return destination ? h.coordinator.takeFromRouted("empty", Duration.ofSeconds(3)) :
                        h.coordinator.takeForRouting(Duration.ofSeconds(3));
            });
            awaitWaiting(thread);
            h.coordinator.beginShutdown();
            assertThatThrownBy(() -> waiting.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(OutboundStorageException.class);
            fails(() -> h.coordinator.accept(source(), new Payload("new")), UNAVAILABLE);
            fails(() -> h.coordinator.selectToRouter(1), UNAVAILABLE);
            h.coordinator.complete(delivery.work.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
        }
    }

    @Test
    void publicationWakesCoordinatorTakes() throws Exception {
        try (var h = new Harness().start(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AtomicReference<Thread> thread = new AtomicReference<>();
            var waiting = executor.submit(() -> {
                thread.set(Thread.currentThread());
                return h.coordinator.takeForRouting(Duration.ofSeconds(3));
            });
            awaitWaiting(thread);
            h.coordinator.accept(source(), new Payload("one"));
            h.coordinator.selectToRouter(1);
            var selected = waiting.get(1, TimeUnit.SECONDS).orElseThrow();
            thread.set(null);
            var destination = executor.submit(() -> {
                thread.set(Thread.currentThread());
                return h.coordinator.takeFromRouted("A", Duration.ofSeconds(3));
            });
            awaitWaiting(thread);
            h.coordinator.recordToRouted(selected, destination("A", "one"));
            var taken = destination.get(1, TimeUnit.SECONDS).orElseThrow();
            h.coordinator.complete(taken.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
        }
    }

    @Test
    void closeDrainsWorkBackToItsStageWithoutCompletingSources() throws Exception {
        try (var h = new Harness().start()) {
            var delivery = h.delivery();
            var routing = h.select("not routed");
            h.coordinator.beginShutdown();
            h.coordinator.close();
            var order = inOrder(h.router, h.routed, h.pending);
            order.verify(h.router).release(routing);
            order.verify(h.routed).release(delivery.work);
            order.verify(h.routed).close();
            order.verify(h.router).close();
            order.verify(h.pending).close();
            verify(h.pending, never()).complete(any());
            fails(h.coordinator::start, INVALID_TRANSITION);
        }
    }

    @Test
    void closeWaitsForHandoffResolutionAndRemainsRetryableAfterDrainFailure() throws Exception {
        try (var h = new Harness().start()) {
            var delivery = h.delivery();
            var handoff = new CompletableFuture<Void>();
            var completed = h.coordinator.complete(delivery.work.id(), handoff);
            fails(h.coordinator::close, UNAVAILABLE);
            verify(h.pending, never()).close();
            handoff.complete(null);
            completed.toCompletableFuture().join();
            h.coordinator.close();
        }
        try (var h = new Harness().start()) {
            var delivery = h.delivery();
            doThrow(new IllegalStateException("drain failed")).when(h.routed).release(delivery.work);
            assertThatThrownBy(h.coordinator::close).hasMessage("drain failed");
            verify(h.pending, never()).close();
            doCallRealMethod().when(h.routed).release(delivery.work);
            h.coordinator.close();
        }
    }

    @Test
    void concurrentCompletionReportsShareOneHandoffAndOneCleanup() throws Exception {
        try (var h = new Harness().start(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var delivery = h.delivery();
            var handoff = new CompletableFuture<Void>();
            var reports = new ArrayList<java.util.concurrent.Future<CompletionStage<Void>>>();
            for (int i = 0; i < 12; i++) {
                reports.add(executor.submit(() -> h.coordinator.complete(delivery.work.id(), handoff)));
            }
            var result = reports.getFirst().get(2, TimeUnit.SECONDS);
            for (var report : reports) {
                assertThat((Object) report.get(2, TimeUnit.SECONDS)).isSameAs(result);
            }
            verify(h.pending, never()).complete(any());
            handoff.complete(null);
            result.toCompletableFuture().join();
            verify(h.pending, times(1)).complete(delivery.selected.sources());
            verify(h.routed, times(1)).complete(delivery.work.id());
        }
    }

    @Test
    void interruptedTakeDoesNotConsumeLaterWork() throws Exception {
        try (var h = new Harness().start(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AtomicReference<Thread> thread = new AtomicReference<>();
            var waiting = executor.submit(() -> {
                thread.set(Thread.currentThread());
                return h.coordinator.takeForRouting(Duration.ofSeconds(3));
            });
            awaitWaiting(thread);
            thread.get().interrupt();
            assertThatThrownBy(() -> waiting.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(InterruptedException.class);
            var selected = h.select("after interrupt");
            h.coordinator.discard(selected.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
        }
    }

    @Test
    void closeAttemptsAllStoresAndPreservesFailures() {
        try (var h = new Harness().start()) {
            var first = new IllegalStateException("routed close");
            var second = new IllegalStateException("router close");
            doThrow(first).when(h.routed).close();
            doThrow(second).when(h.router).close();
            assertThatThrownBy(h.coordinator::close).isSameAs(first);
            assertThat(first.getSuppressed()).containsExactly(second);
            verify(h.pending).close();
        }
    }

    private static void awaitWaiting(AtomicReference<Thread> thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while ((thread.get() == null || thread.get().getState() != Thread.State.TIMED_WAITING) && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(thread.get()).isNotNull();
        assertThat(thread.get().getState()).isEqualTo(Thread.State.TIMED_WAITING);
    }

    private static Destination<Payload> destination(String name, String body) {
        return new Destination<>(name, new Payload(body));
    }

    private static SourceId source() {
        return new SourceId(UUID.randomUUID());
    }

    private static void fails(ThrowingCallable operation, OutboundStorageException.Reason reason) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(OutboundStorageException.class,
                failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    private record Delivery(Selected<Payload> selected, Routed<Payload> work) {
        private SourceId source() {
            return selected.sources().iterator().next();
        }
    }

    private static final class Harness implements AutoCloseable {
        private final MemoryPendingMessageStore<Payload> pending = spy(new MemoryPendingMessageStore<>(4, Payload::copy));
        private final MemorySelectedRouterStore<Payload> router = spy(new MemorySelectedRouterStore<>(pending, 4));
        private final MemoryRoutedWorkStore<Payload> routed;
        private final DefaultOutboundCoordinator<Payload> coordinator;

        private Harness() {
            this(4);
        }

        private Harness(int routedCapacity) {
            routed = spy(new MemoryRoutedWorkStore<>(routedCapacity, Payload::copy));
            coordinator = new DefaultOutboundCoordinator<>(pending, router, routed);
        }

        private Harness start() {
            coordinator.start();
            return this;
        }

        private Selected<Payload> select(String body) throws InterruptedException {
            coordinator.accept(source(), new Payload(body));
            coordinator.selectToRouter(1);
            return coordinator.takeForRouting(Duration.ZERO).orElseThrow();
        }

        private Delivery delivery() throws InterruptedException {
            var selected = select("original");
            coordinator.recordToRouted(selected, new Destination<>("A", selected.message()));
            return new Delivery(selected, coordinator.takeFromRouted("A", Duration.ZERO).orElseThrow());
        }

        @Override
        public void close() {
            coordinator.close();
        }
    }

    private static final class Payload extends StandardMessage {
        private final List<String> tags = new ArrayList<>();

        private Payload(String body) {
            this.body = body;
        }

        private Payload copy() {
            var copy = new Payload(body);
            copy.tags.addAll(tags);
            return copy;
        }
    }
}
