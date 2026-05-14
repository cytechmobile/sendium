package gr.cytech.sendium.routing;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Strings;

import java.util.ArrayList;
import java.util.List;

public class RoutingTable {
    public List<RoutingRule> rules;
    public TargetFunction targetFunction;
    public String name;
    public String label;

    @JsonCreator
    public RoutingTable(
            @JsonProperty("name") String name, @JsonProperty("targetFunction") String targetFunction,
            @JsonProperty("rules") List<RoutingRule> rules, @JsonProperty("label") String label) {
        this(name, TargetFunction.fromName(targetFunction), rules, label);
    }

    public RoutingTable(String name, TargetFunction targetFunction, List<RoutingRule> rules) {
        this(name, targetFunction, rules, null);
    }

    public RoutingTable(String name, TargetFunction targetFunction) {
        this(name, targetFunction, new ArrayList<>(), null);
    }

    public RoutingTable(String name, TargetFunction targetFunction, List<RoutingRule> rules, String label) {
        this.name = name;
        this.rules = rules;
        this.targetFunction = targetFunction;
        this.label = label;
    }

    public List<RoutingRule> getRules() {
        return rules;
    }

    public TargetFunction getTargetFunction() {
        return targetFunction;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "{" +
                "\"name\": \"" + name + "\"," +
                "\"targetFunction\":\"" + targetFunction + "\"," +
                "\"label\":\"" + Strings.nullToEmpty(label) + "\"," +
                "\"rules\": " + rules +
                '}';
    }

    public enum TargetFunction {
        /**
         * Normal target, router should route if {@link RoutingRule rule} matches for the specified {@link gr.cytech.smsp.Message message}.
         */
        NORMAL,
        /**
         * Least cost routing (LCR), router should route according to the smallest cost,
         * based on the {@link RoutingRule#getTarget() target} and not if {@link RoutingRule rule} matches.
         */
        LCR,

        /**
         * Routing class (RC), router should route based on the nextTarget field of the {@link gr.cytech.smsp.Message message}
         * or to the first rule if the nextTarget is not set.
         */
        RC;

        public static TargetFunction fromName(String name) {
            if (Strings.isNullOrEmpty(name)) {
                return NORMAL;
            }
            try {
                return valueOf(name.toUpperCase());
            } catch (Exception e) {
                return NORMAL;
            }
        }
    }

    public enum TargetType {
        TABLE, DESTINATION
    }
}
