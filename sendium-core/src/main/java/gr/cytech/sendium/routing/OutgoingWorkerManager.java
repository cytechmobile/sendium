package gr.cytech.sendium.routing;

import gr.cytech.sendium.core.AbstractOutWorker;

import java.util.Collection;

public interface OutgoingWorkerManager {

    boolean stop();

    Collection<AbstractOutWorker> getWorkersCopy();
}
