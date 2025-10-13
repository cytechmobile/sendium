package smsgateway.routing.config;

public class RoutingRule {

    private String ruleName;
    private Conditions conditions;
    private String destinationId; // ID of the direct handler if this rule is terminal
    private String
            nextRuleGroupName; // Name of the next group of rules to evaluate if this rule matches

    public RoutingRule() {
        // Default constructor
    }

    public RoutingRule(
            String ruleName,
            Conditions conditions,
            String destinationId,
            String nextRuleGroupName) {
        this.ruleName = ruleName;
        this.conditions = conditions;
        this.destinationId = destinationId;
        this.nextRuleGroupName = nextRuleGroupName;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public Conditions getConditions() {
        return conditions;
    }

    public void setConditions(Conditions conditions) {
        this.conditions = conditions;
    }

    public String getDestinationId() {
        return destinationId;
    }

    public void setDestinationId(String destinationId) {
        this.destinationId = destinationId;
    }

    public String getNextRuleGroupName() {
        return nextRuleGroupName;
    }

    public void setNextRuleGroupName(String nextRuleGroupName) {
        this.nextRuleGroupName = nextRuleGroupName;
    }

    @Override
    public String toString() {
        return "RoutingRule{"
                + "ruleName='"
                + ruleName
                + '\''
                + ", conditions="
                + conditions
                + ", destinationId='"
                + destinationId
                + '\''
                + ", nextRuleGroupName='"
                + nextRuleGroupName
                + '\''
                + '}';
    }

    // A rule should ideally have a destinationId (for a terminal action)
    // OR a nextRuleGroupName (to chain to another rule group), but not typically both.
    // The routing logic will enforce how these are interpreted.

    public static class Conditions {
        private String sender;
        private String recipient;
        private String textContains;
        private String textMatchesRegex;

        public Conditions() {
            // Default constructor
        }

        public Conditions(
                String sender, String recipient, String textContains, String textMatchesRegex) {
            this.sender = sender;
            this.recipient = recipient;
            this.textContains = textContains;
            this.textMatchesRegex = textMatchesRegex;
        }

        public String getSender() {
            return sender;
        }

        public void setSender(String sender) {
            this.sender = sender;
        }

        public String getRecipient() {
            return recipient;
        }

        public void setRecipient(String recipient) {
            this.recipient = recipient;
        }

        public String getTextContains() {
            return textContains;
        }

        public void setTextContains(String textContains) {
            this.textContains = textContains;
        }

        public String getTextMatchesRegex() {
            return textMatchesRegex;
        }

        public void setTextMatchesRegex(String textMatchesRegex) {
            this.textMatchesRegex = textMatchesRegex;
        }

        @Override
        public String toString() {
            return "Conditions{"
                    + "sender='"
                    + sender
                    + '\''
                    + ", recipient='"
                    + recipient
                    + '\''
                    + ", textContains='"
                    + textContains
                    + '\''
                    + ", textMatchesRegex='"
                    + textMatchesRegex
                    + '\''
                    + '}';
        }
    }
}
