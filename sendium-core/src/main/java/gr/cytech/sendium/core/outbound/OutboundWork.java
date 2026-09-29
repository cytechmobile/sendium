package gr.cytech.sendium.core.outbound;

import gr.cytech.sendium.core.message.StandardMessage;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Storage identity types are independent of protocol message IDs. An ingress may reuse its gateway
 * message UUID as a source ID; later stage IDs still identify their own lifecycle steps.
 * Message-bearing values contain mutable execution projections. Mutations are intentional but must
 * not silently change an earlier-stage record; implementations keep recorded payload and stage consistent.
 */
public final class OutboundWork {
    private OutboundWork() {
    }

    private static Set<SourceId> copySources(Set<SourceId> sources) {
        Set<SourceId> copy = Set.copyOf(sources);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("Work must retain at least one source");
        }
        return copy;
    }

    private static String requireDestination(String destination) {
        Objects.requireNonNull(destination, "destination");
        if (destination.isBlank()) {
            throw new IllegalArgumentException("Destination must not be blank");
        }
        return destination;
    }

    public record SourceId(UUID value) {
        public SourceId {
            Objects.requireNonNull(value, "value");
        }
    }

    public record SelectionId(UUID value) {
        public SelectionId {
            Objects.requireNonNull(value, "value");
        }
    }

    public record WorkId(UUID value) {
        public WorkId {
            Objects.requireNonNull(value, "value");
        }
    }

    public record Selected<M extends StandardMessage>(SelectionId id, Set<SourceId> sources, M message) {
        public Selected {
            Objects.requireNonNull(id, "id");
            sources = copySources(sources);
            Objects.requireNonNull(message, "message");
        }

        @Override
        public String toString() {
            return "Selected[sourceCount=" + sources.size() + "]";
        }
    }

    public record Destination<M extends StandardMessage>(String name, M message) {
        public Destination {
            name = requireDestination(name);
            Objects.requireNonNull(message, "message");
        }

        @Override
        public String toString() {
            return "Destination[message=redacted]";
        }
    }

    /**
     * A single-destination routing decision. Stores must record its payload and destination consistently
     * before acknowledging the assignment. Copied routes are not representable by this contract.
     */
    public record Assignment<M extends StandardMessage>(SelectionId selection, Set<SourceId> sources,
                                                        Destination<M> destination) {
        public Assignment {
            Objects.requireNonNull(selection, "selection");
            sources = copySources(sources);
            Objects.requireNonNull(destination, "destination");
        }

        @Override
        public String toString() {
            return "Assignment[sourceCount=" + sources.size() + "]";
        }
    }

    public record Routed<M extends StandardMessage>(WorkId id, SelectionId selection, String destination, M message) {
        public Routed {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(selection, "selection");
            destination = requireDestination(destination);
            Objects.requireNonNull(message, "message");
        }

        @Override
        public String toString() {
            return "Routed[message=redacted]";
        }
    }

    public record Completed(SelectionId selection, Set<SourceId> sources) {
        public Completed {
            Objects.requireNonNull(selection, "selection");
            sources = copySources(sources);
        }
    }
}
