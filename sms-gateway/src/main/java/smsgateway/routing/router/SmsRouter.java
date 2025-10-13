package smsgateway.routing.router;

import com.google.common.base.Strings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import smsgateway.dto.DlrForwardingPayload;
import smsgateway.dto.IncomingSms;
import smsgateway.providers.LogProvider;
import smsgateway.routing.config.RoutingRule;
import smsgateway.routing.config.RoutingRule.Conditions;
import smsgateway.routing.destination.MessageDestination;
import smsgateway.routing.loader.RoutingRuleLoader;
import smsgateway.services.DlrForwardingService;
import smsgateway.services.DlrMappingService;
import smsgateway.smpp.DynamicVendorConfigWatcher;

@ApplicationScoped
public class SmsRouter {
    private final Logger logger;
    private final RoutingRuleLoader ruleLoader;
    private DynamicVendorConfigWatcher dynamicVendorConfigWatcher;
    private DlrMappingService dlrMappingService;
    private DlrForwardingService dlrForwardingService;

    public SmsRouter(
            RoutingRuleLoader ruleLoader,
            DynamicVendorConfigWatcher dynamicVendorConfigWatcher,
            Logger logger) {
        this.ruleLoader = ruleLoader;
        this.logger = logger;
        this.dynamicVendorConfigWatcher = dynamicVendorConfigWatcher;
    }

    @Inject // CDI constructor
    public SmsRouter(
            RoutingRuleLoader ruleLoader,
            DynamicVendorConfigWatcher dynamicVendorConfigWatcher,
            DlrMappingService dlrMappingService,
            DlrForwardingService dlrForwardingService) {
        this(
                ruleLoader,
                dynamicVendorConfigWatcher,
                LogProvider.getRoutingLogger(SmsRouter.class.getName()));
        this.dynamicVendorConfigWatcher = dynamicVendorConfigWatcher;
        this.dlrMappingService = dlrMappingService;
        this.dlrForwardingService = dlrForwardingService;
    }

    public void route(IncomingSms message) {
        DlrForwardingPayload payload = new DlrForwardingPayload();
        payload.setForwardingId(message.getInternalId());
        payload.setStatus("ACCEPTED"); // Consider using constants for statuses
        payload.setReceivedAt(Instant.now());
        payload.setFromAddress(message.getFrom());
        payload.setToAddress(message.getTo());
        payload.setOriginatingSessionId(message.getSessionId());
        payload.setBody(message.getText());
        dlrMappingService.storeDlrPayload(message.getInternalId(), payload);
        boolean handled =
                processMessageRecursive(message, ruleLoader.getDefaultGroupName(), new HashSet<>());
        if (!handled) {
            logger.info(
                    "SMS from {} to {} was not handled by any rule or chain.",
                    (message.getFrom() != null ? message.getFrom() : "unknown sender"),
                    (message.getTo() != null ? message.getTo() : "unknown recipient"));
            payload = dlrMappingService.getDlrPayload(message.getInternalId());
            payload.setStatus("FAILED"); // Consider using constants for statuses
            payload.setErrorCode("999"); // Consider using constants for error codes / NO ROUTE
            payload.setProcessedAt(Instant.now());
            dlrMappingService.storeDlrPayload(message.getInternalId(), payload);
            if (dlrForwardingService != null) {
                dlrForwardingService.forwardDlr(payload, "-");
            }
        }
    }

    // Changed from private to package-private for testing
    boolean processMessageRecursive(
            IncomingSms message, String currentGroupName, Set<String> visitedGroupsInPath) {
        if (visitedGroupsInPath.contains(currentGroupName)) {
            logger.error(
                    "Loop detected: trying to re-process rule group: {}. Path: {}",
                    currentGroupName,
                    visitedGroupsInPath);
            return false;
        }

        Set<String> nextVisitedGroups = new HashSet<>(visitedGroupsInPath);
        nextVisitedGroups.add(currentGroupName);

        List<RoutingRule> rules = ruleLoader.getRulesForGroup(currentGroupName);

        if (rules == null || rules.isEmpty()) {
            logger.info("No rules found for group: {} or group not defined.", currentGroupName);
            return false; // No rules in this group, so message not handled by this group
        }

        logger.info("Processing rule group: {} with {} rules.", currentGroupName, rules.size());

        for (var rule : rules) {
            if (conditionsMatch(message, rule.getConditions())) {
                logger.info(
                        "Matched rule '{}' in group '{}'.", rule.getRuleName(), currentGroupName);

                // 1. If nextRuleGroupName exists, recursively call processMessageRecursive.
                if (!Strings.isNullOrEmpty(rule.getNextRuleGroupName())) {
                    logger.info("Chaining to rule group: {}", rule.getNextRuleGroupName());
                    if (processMessageRecursive(
                            message, rule.getNextRuleGroupName(), nextVisitedGroups)) {
                        return true; // Message handled by a subsequent rule group.
                    }
                    logger.info(
                            "Chain from rule '{}' to group '{}' did not handle the message. Checking current rule's destinationId.",
                            rule.getRuleName(),
                            rule.getNextRuleGroupName());
                }

                // 2. If not handled by chained group (or no chained group), and destinationId
                // exists, process with MessageDestination.
                if (!Strings.isNullOrEmpty(rule.getDestinationId())) {
                    MessageDestination handler =
                            dynamicVendorConfigWatcher
                                    .getWorkersActive()
                                    .get(rule.getDestinationId());
                    message.setGateway(rule.getDestinationId());
                    if (handler != null) {
                        logger.info(
                                "Executing destinationId: {} for rule '{}'.",
                                rule.getDestinationId(),
                                rule.getRuleName());
                        handler.process(message, rule.getRuleName(), rule.getDestinationId());
                        return true; // Message handled by this terminal rule's destination.
                    }
                    logger.warn(
                            "No handler found for destinationId: {} for rule '{}'. Rule considered handled (matched).",
                            rule.getDestinationId(),
                            rule.getRuleName());
                    return true; // Rule matched and specified an action (even if handler is
                    // missing).
                }

                // 3. If no nextRuleGroupName and no destinationId (or empty destinationId and not
                // handled by chain),
                // or if destinationId was present but handler was missing (already returned true
                // above).
                // This condition means the rule matched, but there was no next group AND no (valid)
                // destination.
                if (Strings.isNullOrEmpty(rule.getNextRuleGroupName())
                        && Strings.isNullOrEmpty(rule.getDestinationId())) {
                    logger.warn(
                            "Rule '{}' in group '{}' matched but has no action (no nextRuleGroupName or destinationId). Rule considered handled (matched).",
                            rule.getRuleName(),
                            currentGroupName);
                    return true; // Message considered handled as it matched a rule without a
                    // specific actionable step.
                }
                // If we reached here, it means:
                // - There was a nextRuleGroupName, but it didn't handle the message
                // (handledByNextGroup is false).
                // - AND there was no destinationId for the current rule OR the destinationId was
                // processed (and returned true already).
                // This state implies that if a nextRuleGroupName existed but didn't handle, and no
                // destinationId was on the current rule,
                // the message is not handled by THIS rule, so we should continue to the next rule
                // in the loop.
                // The original logic had a slight ambiguity here. The new logic is:
                // - If chain exists and handles -> return true
                // - Else if destination exists and handles -> return true
                // - Else if no chain AND no destination (rule is a "match only") -> return true (as
                // per original log and intent)
                // - Else (e.g. chain existed but didn't handle, and no destination on current rule)
                // -> continue to next rule.
                // This final "else" is implicitly handled by the loop continuing.
            }
        }
        logger.info("No rule matched in group: {}", currentGroupName);
        return false;
    }

    // Changed from private to package-private for testing (indirectly via conditionsMatch)
    boolean checkSenderCondition(IncomingSms message, String senderCondition) {
        if (senderCondition == null || senderCondition.isEmpty()) {
            return true;
        }
        if (message.getFrom() == null) {
            return false;
        }
        if (senderCondition.endsWith("*")) {
            return message.getFrom()
                    .startsWith(senderCondition.substring(0, senderCondition.length() - 1));
        } else {
            return senderCondition.equals(message.getFrom());
        }
    }

    // Changed from private to package-private for testing (indirectly via conditionsMatch)
    boolean checkRecipientCondition(IncomingSms message, String recipientCondition) {
        if (recipientCondition == null || recipientCondition.isEmpty()) {
            return true;
        }
        if (message.getTo() == null) {
            return false;
        }
        if (recipientCondition.endsWith("*")) {
            return message.getTo()
                    .startsWith(recipientCondition.substring(0, recipientCondition.length() - 1));
        } else {
            return recipientCondition.equals(message.getTo());
        }
    }

    // Changed from private to package-private for testing
    boolean conditionsMatch(IncomingSms message, Conditions conditions) {
        if (conditions == null) {
            return true;
        }

        // If conditions object exists but is empty, it's a match-all for this rule IF all
        // sub-conditions are empty/null.
        if (!checkSenderCondition(message, conditions.getSender())) {
            return false;
        }

        if (!checkRecipientCondition(message, conditions.getRecipient())) {
            return false;
        }

        if (conditions.getTextContains() != null && !conditions.getTextContains().isEmpty()) {
            if (message.getText() == null
                    || !message.getText().contains(conditions.getTextContains())) {
                return false;
            }
        }

        if (conditions.getTextMatchesRegex() != null
                && !conditions.getTextMatchesRegex().isEmpty()) {
            if (message.getText() == null
                    || !message.getText().matches(conditions.getTextMatchesRegex())) {
                return false;
            }
        }

        return true; // All conditions passed or were not applicable
    }
}
