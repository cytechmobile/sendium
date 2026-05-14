package gr.cytech.sendium.routing;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Strings;
import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.core.message.CoreMessage;
import gr.cytech.sendium.core.message.StandardMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE,
        creatorVisibility = JsonAutoDetect.Visibility.PUBLIC_ONLY,
        fieldVisibility = JsonAutoDetect.Visibility.NONE)
public class RoutingRule {
    public static final String MULTI_RULE_SEPARATOR = "~~";
    private static CoreMessage staticMsg = new StandardMessage();

    public List<Condition> conditions;
    public String stringValue;
    protected String label;
    protected String target;
    protected boolean copied;
    protected String targetType;

    @JsonCreator
    public RoutingRule(
            // the old way of specifying a rule condition: field-policy-value, possible multiple using MULTI_RULE_SEPARATOR
            @JsonProperty("field") String pField, @JsonProperty("policy") String pPol, @JsonProperty("value") String pValue,
            // the target may contain the copied parameter by having prepended a '+'
            @JsonProperty("target") String pTarget, @JsonProperty("label") String pLabel,
            // the new way of specifying rule conditions
            @JsonProperty("conditions") List<Condition> conditionList, @JsonProperty("copied") String pCopied,
            // explicitly add stringValue and targetType fields here, in order to be ignored if contained in the json
            @JsonProperty("stringValue") String stringValue, @JsonProperty("targetType") String targetType) {
        boolean copiedParam = "true".equals(pCopied);
        if (!Strings.isNullOrEmpty(pPol) || conditionList == null || conditionList.isEmpty()) {
            initRules(pField, pPol, pValue, pTarget, pLabel, copiedParam);
        } else {
            initRules(conditionList, pTarget, pLabel, copiedParam);
        }
    }

    public RoutingRule(String pField, String pPol, String pValue, String pTarget, String pLabel) {
        initRules(pField, pPol, pValue, pTarget, pLabel);
    }

    public RoutingRule(String ruleValue, String comment) {
        String[] parts = ruleValue.split(":");
        final String ruleTarget = parts[0];
        List<String> field = Arrays.stream(parts[1].split(MULTI_RULE_SEPARATOR)).toList();
        List<String> policy = Arrays.stream(parts[2].split(MULTI_RULE_SEPARATOR)).toList();
        List<String> value;
        if (parts.length > 3) {
            String valueField = Arrays.stream(parts).skip(3).collect(Collectors.joining(":"));
            value = Arrays.stream(valueField.split(MULTI_RULE_SEPARATOR)).toList();
        } else {
            value = new ArrayList<>();
            for (int i = 0; i < policy.size(); i++) {
                value.add("");
            }
        }

        // now, if there are comma-separated fields/policies/values, we need to flat map them to singles
        field = field.stream().flatMap(v -> Arrays.stream(v.split(MULTI_RULE_SEPARATOR))).toList();
        policy = policy.stream().flatMap(v -> Arrays.stream(v.split(MULTI_RULE_SEPARATOR))).toList();
        value = value.stream().flatMap(v -> Arrays.stream(v.split(MULTI_RULE_SEPARATOR))).toList();

        initRules(field, policy, value, ruleTarget, comment, false);
    }

    public RoutingRule(List<Condition> pConditions, String pTarget, String pLabel, boolean pCopied) {
        initRules(pConditions, pTarget, pLabel, pCopied);
    }

    private void initRules(String pField, String pPol, String pValue, String pTarget, String pLabel) {
        initRules(pField, pPol, pValue, pTarget, pLabel, false);
    }

    private void initRules(String pField, String pPol, String pValue, String pTarget, String pLabel, boolean pCopied) {
        var field = List.of(Strings.nullToEmpty(pField).split(MULTI_RULE_SEPARATOR));
        var policy = List.of(Strings.nullToEmpty(pPol).split(MULTI_RULE_SEPARATOR));
        var value = List.of(Strings.nullToEmpty(pValue).split(MULTI_RULE_SEPARATOR));
        initRules(field, policy, value, pTarget, pLabel, pCopied);
    }

    private void initRules(List<String> fields, List<String> policies, List<String> values, String ruleTarget, String ruleLabel, boolean pCopied) {
        this.label = ruleLabel;
        if (ruleTarget.startsWith("+")) {
            copied = true;
            target = ruleTarget.substring(1);
        } else {
            copied = pCopied;
            target = ruleTarget;
        }
        this.stringValue = (copied ? "+" : "") +
                target + ":" + String.join(MULTI_RULE_SEPARATOR, fields) + ":" +
                String.join(MULTI_RULE_SEPARATOR, policies) + ":" +
                String.join(MULTI_RULE_SEPARATOR, values);
        this.conditions = new ArrayList<>();
        for (int i = 0; i < policies.size(); i++) {
            this.conditions.add(new Condition(fields.get(i), policies.get(i), values.get(i)));
        }
    }

    private void initRules(List<Condition> conditionList, String ruleTarget, String ruleLabel, boolean pCopied) {
        List<String> fields = new ArrayList<>();
        List<String> policies = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (var r : conditionList) {
            fields.add(r.field);
            policies.add(r.policy);
            values.add(r.value);
        }
        initRules(fields, policies, values, ruleTarget, ruleLabel, pCopied);
    }

    public boolean matches(CoreMessage pMsg, AbstractOutWorker workerTarget) {
        for (var condition : conditions) {
            if (!condition.matches(pMsg, workerTarget)) {
                return false;
            }
        }
        return true;
    }

    public static void setStaticMsg(CoreMessage message) {
        staticMsg = message;
    }

    @JsonProperty("target")
    public String getTarget() {
        return target;
    }

    @JsonProperty("targetType")
    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    /**
     * Retrieves the value of the 'stringValue' field.
     *
     * @return The value of the 'stringValue' field.
     * @deprecated This method is marked for removal and should not be used.
     */
    @Deprecated(forRemoval = true)
    @JsonProperty("stringValue")
    public String getStringValue() {
        return stringValue;
    }

    @Deprecated(forRemoval = true)
    @JsonProperty("field")
    public String getField() {
        return conditions.stream().map(Condition::getField).collect(Collectors.joining(MULTI_RULE_SEPARATOR));
    }

    @Deprecated(forRemoval = true)
    @JsonProperty("policy")
    public String getPolicy() {
        return conditions.stream().map(Condition::getPolicy).collect(Collectors.joining(MULTI_RULE_SEPARATOR));
    }

    @Deprecated(forRemoval = true)
    @JsonProperty("value")
    public String getValue() {
        return conditions.stream().map(Condition::getValue).collect(Collectors.joining(MULTI_RULE_SEPARATOR));
    }

    @JsonProperty("label")
    public String getLabel() {
        return label;
    }

    @Deprecated(forRemoval = true)
    @JsonProperty("copied")
    public boolean isCopied() {
        return copied;
    }

    @JsonProperty("conditions")
    public List<Condition> getConditions() {
        return conditions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        RoutingRule route = (RoutingRule) o;
        return Objects.equals(stringValue, route.stringValue);
    }

    @Override
    public int hashCode() {
        return Strings.nullToEmpty(stringValue).hashCode();
    }

    @Override
    public String toString() {
        return stringValue;
    }

    public enum RouteField { MESSAGE, TARGET }

    public enum TargetField {
        queueSize, marginPercentage, marginStatus, sentFailRate, sentDeliveredRate, sentPendingRate;
        public static final String TARGET_FIELD_PREFIX = "target.";
    }

    @JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE,
            isGetterVisibility = JsonAutoDetect.Visibility.NONE,
            setterVisibility = JsonAutoDetect.Visibility.NONE,
            creatorVisibility = JsonAutoDetect.Visibility.PUBLIC_ONLY,
            fieldVisibility = JsonAutoDetect.Visibility.NONE)
    public static class Condition {
        private int fieldIdx;
        private int polIdx;
        private String field;
        private String policy;
        private String value;
        private Matcher pattern;
        private boolean negator;
        private int polTp;
        private int intValue;
        private boolean booleanValue;
        private long longValue;
        private float floatValue;
        private short shortValue;
        private char charValue;
        private byte byteValue;
        // whether the condition is on one of the routed message's fields (the default),
        // or of the matching routing target (e.g. current queue size)
        private RouteField routingField;
        private TargetField targetRoutingField;

        @JsonCreator
        public Condition(@JsonProperty("field") String pField, @JsonProperty("policy") String pPol, @JsonProperty("value") String pValue) {
            field = pField;
            policy = pPol;
            value = pValue;
            initDefaultValues();
            parsePolicy();
            if (polTp != 0) { // default (matches always)
                parseField();
                parseValue();
            }
        }

        private void initDefaultValues() {
            negator = false;
            pattern = null;
            fieldIdx = 0;
            polIdx = -1;
            polTp = -1;
            intValue = 0;
            booleanValue = false;
            longValue = 0;
            floatValue = 0;
            shortValue = 0;
            charValue = 0;
            byteValue = 0;
        }

        private void parseField() throws IllegalArgumentException {
            Integer msgFieldIdx = staticMsg.getFieldMap().get(field);
            if (msgFieldIdx == null) {
                if (field.startsWith(TargetField.TARGET_FIELD_PREFIX)) {
                    field = field.substring(TargetField.TARGET_FIELD_PREFIX.length());
                }

                //check if the field belongs to the routing target
                if (field.equalsIgnoreCase(TargetField.queueSize.name())) {
                    fieldIdx = 0;
                    routingField = RouteField.TARGET;
                    targetRoutingField = TargetField.queueSize;
                    return;
                } else if (field.equalsIgnoreCase(TargetField.sentPendingRate.name())) {
                    fieldIdx = 0;
                    routingField = RouteField.TARGET;
                    targetRoutingField = TargetField.sentPendingRate;
                    return;
                } else if (field.equalsIgnoreCase(TargetField.sentDeliveredRate.name())) {
                    fieldIdx = 0;
                    routingField = RouteField.TARGET;
                    targetRoutingField = TargetField.sentDeliveredRate;
                    return;
                } else if (field.equalsIgnoreCase(TargetField.sentFailRate.name())) {
                    fieldIdx = 0;
                    routingField = RouteField.TARGET;
                    targetRoutingField = TargetField.sentFailRate;
                    return;
                }
                throw new IllegalArgumentException("Unknown message field name: " + field);
            }
            routingField = RouteField.MESSAGE;
            fieldIdx = msgFieldIdx;
            // just make sure that we won't get an IllegalArgumentException when
            // accessing the Message get<*>Value methods
            switch (polTp) {
                case 0:
                    break; // nothing to check, Route always matches
                case 1:
                    staticMsg.getValue(fieldIdx);
                    break; // String
                case 2:
                    staticMsg.getIntValue(fieldIdx);
                    break; // int
                case 3:
                    staticMsg.getBooleanValue(fieldIdx);
                    break; // boolean
                case 4:
                    staticMsg.getLongValue(fieldIdx);
                    break; // long
                case 5:
                    staticMsg.getFloatValue(fieldIdx);
                    break; // float
                case 6:
                    staticMsg.getShortValue(fieldIdx);
                    break; // short
                case 7:
                    staticMsg.getCharValue(fieldIdx);
                    break; // char
                case 8:
                    staticMsg.getByteValue(fieldIdx);
                    break; // byte
                default:
                    throw new IllegalArgumentException("Unknown policy type");
            }
        }

        private void parseValue() throws IllegalArgumentException {
            if (value == null) {
                throw new IllegalArgumentException(
                        "Parameter value is: null");
            }
            if (polIdx == 3) {
                pattern = Pattern.compile(value).matcher("");
            }
            switch (polTp) {
                case 0:
                    break; // nothing to check, Route always matches
                case 1:
                    break; // String
                case 2:
                    intValue = Integer.parseInt(value);
                    break; // int
                case 3:
                    booleanValue = Boolean.parseBoolean(value);
                    break; // boolean
                case 4:
                    longValue = Long.parseLong(value);
                    break; // long
                case 5:
                    floatValue = Float.parseFloat(value);
                    break; // float
                case 6:
                    shortValue = Short.parseShort(value);
                    break; // short
                case 7:
                    charValue = value.charAt(0);
                    break; // char
                case 8:
                    byteValue = Byte.parseByte(value);
                    break; // byte
                default:
                    throw new IllegalArgumentException("Unknown policy type");
            }
        }

        private void parsePolicy() throws IllegalArgumentException {
            String pol = policy;
            if (pol != null && policy.startsWith("!")) {
                negator = true;
                pol = pol.substring(1);
            }
            if ("default".equals(pol)) {
                polIdx = 0;
                polTp = 0;
            } else if ("equals".equals(pol)) {
                polIdx = 1;
                polTp = 1;
            } else if ("equalsIgnoreCase".equals(pol)) {
                polIdx = 2;
                polTp = 1;
            } else if ("matches".equals(pol)) {
                polIdx = 3;
                polTp = 1;
            } else if ("startsWith".equals(pol)) {
                polIdx = 4;
                polTp = 1;
            } else if ("endsWith".equals(pol)) {
                polIdx = 5;
                polTp = 1;
            } else if ("greaterThan".equals(pol)) {
                polIdx = 6;
                polTp = 1;
            } else if ("greaterEqual".equals(pol)) {
                polIdx = 7;
                polTp = 1;
            } else if ("lessThan".equals(pol)) {
                polIdx = 8;
                polTp = 1;
            } else if ("lessEqual".equals(pol)) {
                polIdx = 9;
                polTp = 1;
            } else if ("isNull".equals(pol)) {
                polIdx = 10;
                polTp = 1;
            } else if ("isTrue".equals(pol)) {
                polIdx = 10;
                polTp = 3;
            } else if ("isFalse".equals(pol)) {
                polIdx = 11;
                polTp = 3;
            } else if ("==".equals(pol)) {
                polIdx = 12;
                polTp = 2;
            } else if (">".equals(pol)) {
                polIdx = 13;
                polTp = 2;
            } else if (">=".equals(pol)) {
                polIdx = 14;
                polTp = 2;
            } else if ("<".equals(pol)) {
                polIdx = 15;
                polTp = 2;
            } else if ("<=".equals(pol)) {
                polIdx = 16;
                polTp = 2;
            } else if ("==i".equals(pol)) {
                polIdx = 12;
                polTp = 2;
            } else if (">i".equals(pol)) {
                polIdx = 13;
                polTp = 2;
            } else if (">=i".equals(pol)) {
                polIdx = 14;
                polTp = 2;
            } else if ("<i".equals(pol)) {
                polIdx = 15;
                polTp = 2;
            } else if ("<=i".equals(pol)) {
                polIdx = 16;
                polTp = 2;
            } else if ("==l".equals(pol)) {
                polIdx = 12;
                polTp = 4;
            } else if (">l".equals(pol)) {
                polIdx = 13;
                polTp = 4;
            } else if (">=l".equals(pol)) {
                polIdx = 14;
                polTp = 4;
            } else if ("<l".equals(pol)) {
                polIdx = 15;
                polTp = 4;
            } else if ("<=l".equals(pol)) {
                polIdx = 16;
                polTp = 4;
            } else if ("==f".equals(pol)) {
                polIdx = 12;
                polTp = 5;
            } else if (">f".equals(pol)) {
                polIdx = 13;
                polTp = 5;
            } else if (">=f".equals(pol)) {
                polIdx = 14;
                polTp = 5;
            } else if ("<f".equals(pol)) {
                polIdx = 15;
                polTp = 5;
            } else if ("<=f".equals(pol)) {
                polIdx = 16;
                polTp = 5;
            } else if ("==s".equals(pol)) {
                polIdx = 12;
                polTp = 6;
            } else if (">s".equals(pol)) {
                polIdx = 13;
                polTp = 6;
            } else if (">=s".equals(pol)) {
                polIdx = 14;
                polTp = 6;
            } else if ("<s".equals(pol)) {
                polIdx = 15;
                polTp = 6;
            } else if ("<=s".equals(pol)) {
                polIdx = 16;
                polTp = 6;
            } else if ("==c".equals(pol)) {
                polIdx = 12;
                polTp = 7;
            } else if (">c".equals(pol)) {
                polIdx = 13;
                polTp = 7;
            } else if (">=c".equals(pol)) {
                polIdx = 14;
                polTp = 7;
            } else if ("<c".equals(pol)) {
                polIdx = 15;
                polTp = 7;
            } else if ("<=c".equals(pol)) {
                polIdx = 16;
                polTp = 7;
            } else if ("==b".equals(pol)) {
                polIdx = 12;
                polTp = 8;
            } else if (">b".equals(pol)) {
                polIdx = 13;
                polTp = 8;
            } else if (">=b".equals(pol)) {
                polIdx = 14;
                polTp = 8;
            } else if ("<b".equals(pol)) {
                polIdx = 15;
                polTp = 8;
            } else if ("<=b".equals(pol)) {
                polIdx = 16;
                polTp = 8;
            } else {
                throw new IllegalArgumentException("Unknown policy name: " + pol);
            }
        }

        public boolean matches(CoreMessage pMsg, AbstractOutWorker target) {
            boolean match;
            switch (polTp) {
                case 0:
                    match = true;
                    break; // Route always matches
                case 1:
                    match = matchString(pMsg);
                    break; // String
                case 2:
                    match = matchInt(pMsg, target);
                    break; // int
                case 3:
                    match = matchBoolean(pMsg);
                    break; // boolean
                case 4:
                    match = matchLong(pMsg);
                    break; // long
                case 5:
                    match = matchFloat(pMsg);
                    break; // float
                case 6:
                    match = matchShort(pMsg);
                    break; // short
                case 7:
                    match = matchChar(pMsg);
                    break; // char
                case 8:
                    match = matchByte(pMsg);
                    break; // byte
                default:
                    match = false;
                    break;
            }
            return negator ? !match : match;
        }

        private boolean matchString(CoreMessage pMsg) {
            String msgVal = pMsg.getValue(fieldIdx);
            if (polIdx != 10 && msgVal == null) {
                return false;
            }
            switch (polIdx) {
                case 1:
                    return msgVal.equals(value);
                case 2:
                    return msgVal.equalsIgnoreCase(value);
                case 3:
                    return pattern.reset(msgVal).matches();
                case 4:
                    return msgVal.startsWith(value);
                case 5:
                    return msgVal.endsWith(value);
                case 6:
                    return msgVal.compareTo(value) > 0;
                case 7:
                    return msgVal.compareTo(value) >= 0;
                case 8:
                    return msgVal.compareTo(value) < 0;
                case 9:
                    return msgVal.compareTo(value) <= 0;
                case 10:
                    return msgVal == null;
                default:
                    return false;
            }
        }

        private boolean matchInt(CoreMessage pMsg, AbstractOutWorker out) {
            int msgVal;
            if (routingField == RouteField.TARGET) {
                if (out == null) {
                    return false;
                }
                switch (targetRoutingField) {
                    case TargetField.queueSize ->  msgVal = out.getMsgQueue().size();
                    case TargetField.sentPendingRate -> msgVal = out.getVendorPendingRate();
                    case TargetField.sentFailRate ->  msgVal = out.getVendorFailRate();
                    case TargetField.sentDeliveredRate ->  msgVal = out.getVendorDeliveredRate();
                    default -> {
                        return  false;
                    }
                }
            } else {
                msgVal = pMsg.getIntValue(fieldIdx);
            }
            switch (polIdx) {
                case 12:
                    return msgVal == intValue;
                case 13:
                    return msgVal > intValue;
                case 14:
                    return msgVal >= intValue;
                case 15:
                    return msgVal < intValue;
                case 16:
                    return msgVal <= intValue;
                default:
                    return false;
            }
        }

        private boolean matchBoolean(CoreMessage pMsg) {
            boolean msgVal = pMsg.getBooleanValue(fieldIdx);
            switch (polIdx) {
                case 10:
                    return msgVal;
                case 11:
                    return !msgVal;
                default:
                    return false;
            }
        }

        private boolean matchLong(CoreMessage pMsg) {
            long msgVal = pMsg.getLongValue(fieldIdx);
            switch (polIdx) {
                case 12:
                    return msgVal == longValue;
                case 13:
                    return msgVal > longValue;
                case 14:
                    return msgVal >= longValue;
                case 15:
                    return msgVal < longValue;
                case 16:
                    return msgVal <= longValue;
                default:
                    return false;
            }
        }

        private boolean matchFloat(CoreMessage pMsg) {
            float msgVal = pMsg.getFloatValue(fieldIdx);
            switch (polIdx) {
                case 12:
                    return msgVal == floatValue;
                case 13:
                    return msgVal > floatValue;
                case 14:
                    return msgVal >= floatValue;
                case 15:
                    return msgVal < floatValue;
                case 16:
                    return msgVal <= floatValue;
                default:
                    return false;
            }
        }

        private boolean matchShort(CoreMessage pMsg) {
            short msgVal = pMsg.getShortValue(fieldIdx);
            switch (polIdx) {
                case 12:
                    return msgVal == shortValue;
                case 13:
                    return msgVal > shortValue;
                case 14:
                    return msgVal >= shortValue;
                case 15:
                    return msgVal < shortValue;
                case 16:
                    return msgVal <= shortValue;
                default:
                    return false;
            }
        }

        private boolean matchChar(CoreMessage pMsg) {
            char msgVal = pMsg.getCharValue(fieldIdx);
            switch (polIdx) {
                case 12:
                    return msgVal == charValue;
                case 13:
                    return msgVal > charValue;
                case 14:
                    return msgVal >= charValue;
                case 15:
                    return msgVal < charValue;
                case 16:
                    return msgVal <= charValue;
                default:
                    return false;
            }
        }

        private boolean matchByte(CoreMessage pMsg) {
            byte msgVal = pMsg.getByteValue(fieldIdx);
            switch (polIdx) {
                case 12:
                    return msgVal == byteValue;
                case 13:
                    return msgVal > byteValue;
                case 14:
                    return msgVal >= byteValue;
                case 15:
                    return msgVal < byteValue;
                case 16:
                    return msgVal <= byteValue;
                default:
                    return false;
            }
        }

        @JsonProperty("field")
        public String getField() {
            return field;
        }

        @JsonProperty("policy")
        public String getPolicy() {
            return policy;
        }

        @JsonProperty("value")
        public String getValue() {
            return value;
        }
    }
}