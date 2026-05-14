package gr.cytech.sendium.routing;

import gr.cytech.sendium.core.AbstractOutWorker;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class RoutingLookupResult {

    public static final RoutingLookupResult EMPTY_RESULT = new UnmodifiableNoResultRoutingLookup();
    private final List<AbstractOutWorker> destinations;
    private boolean reachedLast;

    public RoutingLookupResult(List<AbstractOutWorker> destinations, boolean reachedLast) {
        this.destinations = Objects.requireNonNull(destinations, "Destinations cannot be null");
        this.reachedLast = reachedLast;
    }

    public void addDestinations(List<AbstractOutWorker> moreDestinations) {
        this.destinations.addAll(moreDestinations);
    }

    public void addDestination(AbstractOutWorker destination) {
        this.destinations.add(destination);
    }

    public List<AbstractOutWorker> getDestinations() {
        return destinations;
    }

    public void setReachedLast(boolean reachedLast) {
        this.reachedLast = reachedLast;
    }

    public boolean hasReachedLast() {
        return reachedLast;
    }

    public void mergeRoutingLookupResult(RoutingLookupResult result) {
        if (result != null) {
            addDestinations(result.getDestinations());
            setReachedLast(result.hasReachedLast());
        }
    }

    private static class UnmodifiableNoResultRoutingLookup extends RoutingLookupResult {
        public UnmodifiableNoResultRoutingLookup() {
            super(Collections.emptyList(), false);
        }

        public void setReachedLast(boolean reachedLast) {
            //Nothing to do, cannot alter it
        }
    }
}
