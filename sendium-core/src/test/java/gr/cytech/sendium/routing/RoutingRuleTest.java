package gr.cytech.sendium.routing;

import gr.cytech.sendium.core.message.StandardMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingRuleTest {

    @Test
    void marginPercentageDoubleAccessorPreservesPrecision() {
        StandardMessage msg = new StandardMessage();

        msg.setValue(StandardMessage.fieldMap.get("marginPercentage"), "1.00000008");

        assertEquals(1.00000008, msg.getDoubleValue(StandardMessage.fieldMap.get("marginPercentage")));
        assertThrows(IllegalArgumentException.class, () -> msg.getFloatValue(StandardMessage.fieldMap.get("marginPercentage")));
    }

    @Test
    void marginPercentageRoutingUsesDoublePrecision() {
        StandardMessage msg = new StandardMessage();
        msg.marginPercentage = 1.00000008;

        RoutingRule equalsRoundedFloatRule =
                new RoutingRule("marginPercentage", "==f", "1.00000007", "target", "label");
        RoutingRule greaterThanRule =
                new RoutingRule("marginPercentage", ">f", "1.00000007", "target", "label");

        assertFalse(equalsRoundedFloatRule.matches(msg, null));
        assertTrue(greaterThanRule.matches(msg, null));
    }

    @Test
    void marginPercentageGreaterThanRuleMatchesAboveThreshold() {
        StandardMessage msg = new StandardMessage();
        msg.marginPercentage = 0.3300000000000001;

        RoutingRule rule = new RoutingRule("marginPercentage", ">f", "0.33", "target", "label");

        assertTrue(rule.matches(msg, null));
    }
}
