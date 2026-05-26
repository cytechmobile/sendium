package gr.cytech.sendium.routing;

import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.core.message.StandardMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StandardRoutingManagerTest {

    @Test
    void parseNewRoutingTableIndexesWorkersByFullAndInstanceName() throws Exception {
        StandardRoutingManager manager = new StandardRoutingManager();
        AbstractOutWorker<StandardMessage> worker = acceptingWorker("smpp.route", "route");
        Map<String, RoutingTable> routes = parseRoutes("route::default:");

        manager.parseNewRoutingTable(routes, List.of(worker));

        assertSame(worker, manager.getTargets().getWorker("smpp.route"));
        assertSame(worker, manager.getTargets().getWorker("route"));
    }

    @Test
    void parseNewRoutingTableKeepsPreviousTargetsWhenDefaultTableIsMissing() {
        StandardRoutingManager manager = new StandardRoutingManager();
        AbstractOutWorker<StandardMessage> worker = acceptingWorker("smpp.route", "route");
        Map<String, RoutingTable> validRoutes = parseRoutes("route::default:");

        manager.parseNewRoutingTable(validRoutes, List.of(worker));
        AbstractRoutingManager.RoutingTargets previousTargets = manager.getTargets();

        manager.parseNewRoutingTable(Map.of("other", new RoutingTable("other", RoutingTable.TargetFunction.NORMAL)), List.of(worker));

        assertSame(previousTargets, manager.getTargets());
    }

    @Test
    void lookupRoutingForMessageReturnsEmptyWhenTargetDoesNotExist() throws Exception {
        StandardRoutingManager manager = new StandardRoutingManager();
        manager.parseNewRoutingTable(parseRoutes("missing::default:"), List.of());

        RoutingLookupResult result = manager.lookupRoutingForMessage(new StandardMessage(), manager.getTargets().defaultTable);

        assertTrue(result.getDestinations().isEmpty());
        assertTrue(!result.hasReachedLast());
    }

    @Test
    void lookupRoutingForMessageResolvesRecursiveTableToWorker() throws Exception {
        StandardRoutingManager manager = new StandardRoutingManager();
        AbstractOutWorker<StandardMessage> worker = acceptingWorker("smpp.route", "route");
        Map<String, RoutingTable> routes = parseRoutes(
                "secondary::default:",
                "[secondary]",
                "route::default:"
        );
        manager.parseNewRoutingTable(routes, List.of(worker));

        RoutingLookupResult result = manager.lookupRoutingForMessage(new StandardMessage(), manager.getTargets().defaultTable);

        assertEquals(1, result.getDestinations().size());
        assertSame(worker, result.getDestinations().getFirst());
        assertTrue(result.hasReachedLast());
    }

    private Map<String, RoutingTable> parseRoutes(String... lines) {
        return RoutingFileParser.parseRoutingTable(List.of(lines));
    }

    private AbstractOutWorker<StandardMessage> acceptingWorker(String fullName, String instanceName) {
        AbstractOutWorker<StandardMessage> worker = mock(AbstractOutWorker.class);
        when(worker.getFullName()).thenReturn(fullName);
        when(worker.getInstanceName()).thenReturn(instanceName);
        when(worker.isFilter()).thenReturn(false);
        when(worker.acceptsMessages()).thenReturn(true);
        return worker;
    }
}
