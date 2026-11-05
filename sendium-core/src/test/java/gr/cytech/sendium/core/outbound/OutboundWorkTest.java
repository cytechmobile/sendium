package gr.cytech.sendium.core.outbound;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Assignment;
import gr.cytech.sendium.core.outbound.OutboundWork.Completed;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.Routed;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.outbound.OutboundWork.WorkId;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundWorkTest {
    @Test
    void assignmentAndCompletionKeepEverySourceDespiteCallerCollectionChanges() {
        SourceId first = new SourceId(UUID.randomUUID());
        SourceId second = new SourceId(UUID.randomUUID());
        Set<SourceId> sources = new HashSet<>(Set.of(first, second));
        SelectionId selection = new SelectionId(UUID.randomUUID());
        var message = new StandardMessage();
        var selected = new Selected<>(selection, sources, message);
        var destination = new Destination<>("provider", message);
        var assignment = new Assignment<>(selection, sources, destination);
        var completed = new Completed(selection, sources);

        sources.clear();

        assertThat(selected.sources()).containsExactlyInAnyOrder(first, second);
        assertThat(assignment.sources()).containsExactlyInAnyOrder(first, second);
        assertThat(completed.sources()).containsExactlyInAnyOrder(first, second);
        assertThat(assignment.destination()).isSameAs(destination);
        assertThatThrownBy(() -> assignment.sources().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> completed.sources().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void assignmentRequiresSourceOwnershipAndOneNamedDestination() {
        var selection = new SelectionId(UUID.randomUUID());
        var sources = Set.of(new SourceId(UUID.randomUUID()));
        var destination = new Destination<>("provider", new StandardMessage());

        assertThatThrownBy(() -> new Assignment<>(selection, sources, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Assignment<>(selection, Set.of(), destination))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Destination<>(" ", new StandardMessage()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void messageBearingValuesNeverInvokeMessageToString() {
        StandardMessage message = new StandardMessage() {
            @Override
            public String toString() {
                throw new AssertionError("Message contents must not be rendered by storage values");
            }
        };
        var selection = new SelectionId(UUID.randomUUID());
        var sources = Set.of(new SourceId(UUID.randomUUID()));
        var destination = new Destination<>("private-endpoint", message);
        var values = List.of(
                new Selected<>(selection, sources, message),
                destination,
                new Assignment<>(selection, sources, destination),
                new Routed<>(new WorkId(UUID.randomUUID()), selection, destination.name(), message));

        for (Object value : values) {
            assertThat(value.toString()).doesNotContain("private-endpoint");
        }
    }
}
