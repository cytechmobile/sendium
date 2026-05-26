package gr.cytech.sendium.routing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingFileParserTest {

    @Test
    void parseRoutingTable_CreatesDefaultTableWithoutExplicitHeader() {
        Map<String, RoutingTable> routes = RoutingFileParser.parseRoutingTable(List.of(
                "worker:from:equals:sender"
        ));

        assertTrue(routes.containsKey(RoutingFileParser.DEFAULT_ROUTING_TABLE_NAME));
        assertEquals(1, routes.get(RoutingFileParser.DEFAULT_ROUTING_TABLE_NAME).getRules().size());
        assertEquals("worker", routes.get(RoutingFileParser.DEFAULT_ROUTING_TABLE_NAME).getRules().getFirst().getTarget());
    }

    @Test
    void parseRoutingTable_AttachesCommentsToNextRuleOrTable() {
        Map<String, RoutingTable> routes = RoutingFileParser.parseRoutingTable(List.of(
                "#default route label",
                "worker:from:equals:sender",
                "#first line",
                "#second line",
                "[marketing]"
        ));

        RoutingRule rule = routes.get(RoutingFileParser.DEFAULT_ROUTING_TABLE_NAME).getRules().getFirst();
        RoutingTable table = routes.get("marketing");

        assertEquals("default route label", rule.getLabel());
        assertEquals("first line\nsecond line", table.getLabel());
    }

    @Test
    void parseRoutingTable_ParsesFunctionTables() {
        Map<String, RoutingTable> routes = RoutingFileParser.parseRoutingTable(List.of(
                "[leastCost->function(LCR)]",
                "worker::default:"
        ));

        RoutingTable table = routes.get("leastCost");

        assertEquals(RoutingTable.TargetFunction.LCR, table.getTargetFunction());
        assertEquals(1, table.getRules().size());
    }

    @Test
    void parseRoutingTable_IgnoresBlankAndInvalidLines() {
        Map<String, RoutingTable> routes = RoutingFileParser.parseRoutingTable(List.of(
                "",
                "not-a-route",
                "worker::default:"
        ));

        RoutingTable defaultTable = routes.get(RoutingFileParser.DEFAULT_ROUTING_TABLE_NAME);

        assertEquals(1, defaultTable.getRules().size());
        assertFalse(defaultTable.getRules().stream().anyMatch(rule -> "not-a-route".equals(rule.toString())));
    }

    @Test
    void parseRoutingTable_PreservesColonsInsideRuleValue() {
        Map<String, RoutingTable> routes = RoutingFileParser.parseRoutingTable(List.of(
                "worker:body:equals:https://example.test/dlr?id=1"
        ));

        RoutingRule rule = routes.get(RoutingFileParser.DEFAULT_ROUTING_TABLE_NAME).getRules().getFirst();

        assertEquals("https://example.test/dlr?id=1", rule.getConditions().getFirst().getValue());
    }
}
