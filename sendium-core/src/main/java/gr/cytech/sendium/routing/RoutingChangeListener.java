package gr.cytech.sendium.routing;

import java.util.Map;

public interface RoutingChangeListener {
    void routingChange(Map<String, RoutingTable> updatedRoutes);
}