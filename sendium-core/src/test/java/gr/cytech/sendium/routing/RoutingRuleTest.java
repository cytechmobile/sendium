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

    @Test
    void copiedRoutePrefixMarksRuleAsCopiedAndStripsTargetPrefix() {
        RoutingRule rule = new RoutingRule("+copyTarget:from:equals:sender", "copy label");

        assertEquals("copyTarget", rule.getTarget());
        assertEquals("+copyTarget:from:equals:sender", rule.toString());
        assertEquals("copy label", rule.getLabel());
    }

    @Test
    void multiConditionRuleRequiresEveryConditionToMatch() {
        StandardMessage msg = new StandardMessage();
        msg.from = "sender";
        msg.to = "306900000000";

        RoutingRule rule = new RoutingRule("target:from~~to:equals~~startsWith:sender~~3069", null);

        assertTrue(rule.matches(msg, null));

        msg.to = "447700000000";

        assertFalse(rule.matches(msg, null));
    }

    @Test
    void negatedStringPolicyInvertsMatch() {
        StandardMessage msg = new StandardMessage();
        msg.owner_id = "account-a";

        RoutingRule rule = new RoutingRule("owner_id", "!equals", "account-b", "target", null);

        assertTrue(rule.matches(msg, null));
    }

    @Test
    void stringPoliciesCoverRegexPrefixSuffixAndNullChecks() {
        StandardMessage msg = new StandardMessage();
        msg.body = "Sendium route check";
        msg.message_center = null;

        assertTrue(new RoutingRule("body", "matches", "Sendium.*check", "target", null).matches(msg, null));
        assertTrue(new RoutingRule("body", "startsWith", "Sendium", "target", null).matches(msg, null));
        assertTrue(new RoutingRule("body", "endsWith", "check", "target", null).matches(msg, null));
        assertTrue(new RoutingRule("message_center", "isNull", "", "target", null).matches(msg, null));
    }

    @Test
    void ruleValueCanContainColonCharacters() {
        StandardMessage msg = new StandardMessage();
        msg.body = "https://example.test/dlr?id=1";

        RoutingRule rule = new RoutingRule("target:body:equals:https://example.test/dlr?id=1", null);

        assertTrue(rule.matches(msg, null));
    }

    @Test
    void unknownFieldOrPolicyFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> new RoutingRule("unknown", "equals", "x", "target", null));
        assertThrows(IllegalArgumentException.class, () -> new RoutingRule("body", "unknownPolicy", "x", "target", null));
    }
}
