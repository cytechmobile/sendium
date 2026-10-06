package gr.cytech.sendium.routing;

import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Routed;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.external.filter.FilterException;
import gr.cytech.sendium.external.filter.FilterStatusCodes;
import org.junit.jupiter.api.Test;
import utils.OutboundIngressFixture;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StandardOutboundDispatchTest {
    @Test
    void interruptedDestinationRecordingRetriesTheOriginalIntentWithoutRepeatingLookup() throws Exception {
        try (var fixture = new OutboundIngressFixture(1)) {
            var provider = worker("smpp.provider", "provider");
            var routing = mock(StandardRoutingManager.class);
            when(routing.lookupForLifecycle(any())).thenReturn(new RoutingLookupResult(List.of(provider), true));
            var dispatch = new StandardOutboundDispatch(fixture.coordinator, routing);
            fixture.coordinator.admit(new SourceId(UUID.randomUUID()), message("hello"));
            fixture.coordinator.selectAndStage(1);
            var selected = fixture.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            doThrow(new IllegalStateException("recording interrupted")).doCallRealMethod()
                    .when(fixture.coordinator).route(any(), any());
            assertThatThrownBy(() -> dispatch.routeSelected(selected)).isInstanceOf(IllegalStateException.class);
            dispatch.retryRoutingTransitions();
            verify(routing, times(1)).lookupForLifecycle(any());
            assertThat(dispatch.takeForProvider("smpp.provider", Duration.ZERO)).isPresent();
            dispatch.close();
        }
    }

    @Test
    void routingFilterDropCompletesTheSourceWithoutProviderDispatch() throws Exception {
        try (var fixture = new OutboundIngressFixture(1)) {
            var routing = mock(StandardRoutingManager.class);
            var dispatch = new StandardOutboundDispatch(fixture.coordinator, routing);
            SourceId source = new SourceId(UUID.randomUUID());
            fixture.coordinator.admit(source, message("hello"));
            fixture.coordinator.selectAndStage(1);
            var selected = fixture.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            when(routing.lookupForLifecycle(any())).thenThrow(new FilterException(null,
                    FilterStatusCodes.DROP, selected.message(), "dropped"));
            assertThat(dispatch.routeSelected(selected)).isEmpty();
            assertThat(fixture.pending.find(source)).isEmpty();
            assertThat(dispatch.takeForProvider("smpp.provider", Duration.ZERO)).isEmpty();
            dispatch.close();
        }
    }

    @Test
    void shutdownDrainsAllHandoffsAndRetainsOwnershipOnTimeoutOrFailure() throws Exception {
        try (var fixture = new OutboundIngressFixture(1)) {
            var dispatch = new StandardOutboundDispatch(fixture.coordinator,
                    routing(List.of(worker("smpp.provider", "provider")), "provider::default:"));
            SourceId source = new SourceId(UUID.randomUUID());
            fixture.coordinator.admit(source, message("hello"));
            routeNext(dispatch, fixture);
            var taken = dispatch.takeForProvider("smpp.provider", Duration.ZERO).orElseThrow();
            var first = new CompletableFuture<Void>();
            var second = new CompletableFuture<Void>();
            dispatch.finishProviderParts(taken, List.of(first, second));
            dispatch.quiesce();
            first.complete(null);
            assertThat(dispatch.awaitProviderDrain(Duration.ZERO)).isFalse();
            assertThatThrownBy(dispatch::close).isInstanceOf(IllegalStateException.class);
            assertThat(fixture.pending.find(source)).isPresent();
            second.completeExceptionally(new IllegalStateException("handoff unavailable"));
            assertThatThrownBy(() -> dispatch.awaitProviderDrain(Duration.ZERO))
                    .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(IllegalStateException.class);
            assertThatThrownBy(dispatch::close).isInstanceOf(IllegalStateException.class);
            dispatch.finishProviderParts(taken, List.of(CompletableFuture.completedStage(null)));
            assertThat(dispatch.awaitProviderDrain(Duration.ZERO)).isTrue();
            assertThat(fixture.pending.find(source)).isEmpty();
            dispatch.close();
        }
    }

    @Test
    void shutdownReturnsUnassignedWorkAndLeavesUnsubmittedWorkAtItsRecordedDestination() throws Exception {
        try (var fixture = new OutboundIngressFixture(2)) {
            var dispatch = new StandardOutboundDispatch(fixture.coordinator,
                    routing(List.of(worker("smpp.provider", "provider")), "provider::default:"));
            fixture.coordinator.admit(new SourceId(UUID.randomUUID()), message("assigned"));
            routeNext(dispatch, fixture);
            var assigned = dispatch.takeForProvider("smpp.provider", Duration.ZERO).orElseThrow();
            fixture.coordinator.admit(new SourceId(UUID.randomUUID()), message("unassigned"));
            fixture.coordinator.selectAndStage(1);
            var selected = fixture.coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            dispatch.quiesce();
            assertThat(dispatch.routeSelected(selected)).isEmpty();
            assertThat(fixture.router.take(Duration.ZERO).orElseThrow().id()).isEqualTo(selected.id());
            fixture.coordinator.returnToDestination(assigned);
            assertThat(fixture.routed.take("smpp.provider", Duration.ZERO).orElseThrow().id()).isEqualTo(assigned.id());
            assertThat(dispatch.awaitProviderDrain(Duration.ZERO)).isTrue();
            dispatch.close();
        }
    }

    @Test
    void routesToOneRecordedDestinationAndRetainsSourcesThroughAllProviderPartHandoffs() throws Exception {
        try (var fixture = new OutboundIngressFixture(2)) {
            var worker = worker("smpp.provider", "provider");
            var routing = routing(List.of(worker), "provider::default:");
            var dispatch = new StandardOutboundDispatch(fixture.coordinator, routing);
            SourceId source = new SourceId(UUID.randomUUID());
            fixture.coordinator.admit(source, message("hello"));

            var routed = routeNext(dispatch, fixture).orElseThrow();
            assertThat(routed.destination()).isEqualTo(worker.getFullName());
            assertThat(dispatch.takeForProvider("other", Duration.ZERO)).isEmpty();
            var taken = dispatch.takeForProvider(worker.getFullName(), Duration.ZERO).orElseThrow();
            assertThat(taken.id()).isEqualTo(routed.id());
            assertThat(fixture.pending.find(source)).isPresent();

            var firstPartHandoff = new CompletableFuture<Void>();
            var secondPartHandoff = new CompletableFuture<Void>();
            var completed = dispatch.finishProviderParts(taken, List.of(firstPartHandoff, secondPartHandoff));
            firstPartHandoff.complete(null);
            assertThat(completed.toCompletableFuture()).isNotDone();
            assertThat(fixture.pending.find(source)).isPresent();
            secondPartHandoff.complete(null);
            completed.toCompletableFuture().join();
            assertThat(fixture.pending.find(source)).isEmpty();
            assertThat(dispatch.takeForProvider(worker.getFullName(), Duration.ZERO)).isEmpty();
            dispatch.finishProviderParts(taken, List.of(CompletableFuture.completedStage(null)))
                    .toCompletableFuture().join();
        }
    }

    @Test
    void failedPartHandoffRetainsWorkForCompletionOnlyRetryWithoutRedispatch() throws Exception {
        try (var fixture = new OutboundIngressFixture(1)) {
            var dispatch = new StandardOutboundDispatch(fixture.coordinator,
                    routing(List.of(worker("smpp.provider", "provider")), "provider::default:"));
            SourceId source = new SourceId(UUID.randomUUID());
            fixture.coordinator.admit(source, message("hello"));
            routeNext(dispatch, fixture).orElseThrow();
            var taken = dispatch.takeForProvider("smpp.provider", Duration.ZERO).orElseThrow();
            var handoff = new CompletableFuture<Void>();
            var completion = dispatch.finishProviderParts(taken, List.of(CompletableFuture.completedStage(null), handoff));
            handoff.completeExceptionally(new IllegalStateException("handoff unavailable"));
            assertThatThrownBy(() -> completion.toCompletableFuture().join()).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(fixture.pending.find(source)).isPresent();
            assertThat(dispatch.takeForProvider("smpp.provider", Duration.ZERO)).isEmpty();

            dispatch.finishProviderParts(taken, List.of(CompletableFuture.completedStage(null),
                    CompletableFuture.completedStage(null))).toCompletableFuture().join();
            assertThat(fixture.pending.find(source)).isEmpty();
        }
    }

    @Test
    void copiedRouteIsRejectedBeforeRecordingWorkEvenWhenItFindsOnlyOneDestination() throws Exception {
        try (var fixture = new OutboundIngressFixture(1)) {
            var provider = worker("smpp.provider", "provider");
            var routing = routing(List.of(provider), "+provider::default:");
            var dispatch = new StandardOutboundDispatch(fixture.coordinator, routing);
            SourceId source = new SourceId(UUID.randomUUID());
            fixture.coordinator.admit(source, message("hello"));

            assertThat(routing.lookupRoutingForMessage(message("legacy"), routing.getTargets().defaultTable)
                    .getDestinations()).containsExactly(provider);
            assertThatThrownBy(() -> routeNext(dispatch, fixture))
                    .isInstanceOfSatisfying(OutboundStorageException.class, failure ->
                            assertThat(failure.reason()).isEqualTo(OutboundStorageException.Reason.UNSUPPORTED));
            assertThat(fixture.pending.find(source)).isPresent();
            assertThat(dispatch.takeForProvider("smpp.provider", Duration.ZERO)).isEmpty();

            routing.parseNewRoutingTable(RoutingFileParser.parseRoutingTable(List.of("provider::default:")), List.of(provider));
            assertThat(routeNext(dispatch, fixture)).isPresent();
            assertThat(dispatch.takeForProvider("smpp.provider", Duration.ZERO)).isPresent();
        }
    }

    @Test
    void copiedRouteInsideNestedTableIsRejectedBeforeAssignment() throws Exception {
        try (var fixture = new OutboundIngressFixture(1)) {
            var provider = worker("smpp.provider", "provider");
            var routing = routing(List.of(provider), "second::default:", "[second]", "+provider::default:");
            var dispatch = new StandardOutboundDispatch(fixture.coordinator, routing);
            fixture.coordinator.admit(new SourceId(UUID.randomUUID()), message("hello"));
            assertThatThrownBy(() -> routeNext(dispatch, fixture))
                    .isInstanceOfSatisfying(OutboundStorageException.class, failure ->
                            assertThat(failure.reason()).isEqualTo(OutboundStorageException.Reason.UNSUPPORTED));
            assertThat(dispatch.takeForProvider("smpp.provider", Duration.ZERO)).isEmpty();
        }
    }

    @Test
    void routingMissReturnsTheUnassignedSelectionForLaterRetry() throws Exception {
        try (var fixture = new OutboundIngressFixture(1)) {
            var provider = worker("smpp.provider", "provider");
            var routing = routing(List.of(provider), "unknown::default:");
            var dispatch = new StandardOutboundDispatch(fixture.coordinator, routing);
            SourceId source = new SourceId(UUID.randomUUID());
            fixture.coordinator.admit(source, message("hello"));
            assertThat(routeNext(dispatch, fixture)).isEmpty();
            assertThat(fixture.pending.find(source)).isPresent();
            routing.parseNewRoutingTable(RoutingFileParser.parseRoutingTable(List.of("provider::default:")), List.of(provider));
            assertThat(routeNext(dispatch, fixture)).isPresent();
        }
    }

    private static StandardMessage message(String body) {
        var message = new StandardMessage();
        message.body = body;
        return message;
    }

    private static Optional<Routed<StandardMessage>> routeNext(StandardOutboundDispatch dispatch,
                                                                OutboundIngressFixture fixture) throws Exception {
        fixture.coordinator.selectAndStage(1);
        return dispatch.routeSelected(fixture.coordinator.takeForRouting(Duration.ZERO).orElseThrow());
    }

    private static StandardRoutingManager routing(List<AbstractOutWorker<StandardMessage>> workers, String... rules) {
        var routing = new StandardRoutingManager();
        Map<String, RoutingTable> tables = RoutingFileParser.parseRoutingTable(List.of(rules));
        routing.parseNewRoutingTable(tables, List.copyOf(workers));
        return routing;
    }

    @SuppressWarnings("unchecked")
    private static AbstractOutWorker<StandardMessage> worker(String fullName, String instanceName) {
        AbstractOutWorker<StandardMessage> worker = mock(AbstractOutWorker.class);
        when(worker.getFullName()).thenReturn(fullName);
        when(worker.getInstanceName()).thenReturn(instanceName);
        when(worker.acceptsMessages()).thenReturn(true);
        return worker;
    }
}
