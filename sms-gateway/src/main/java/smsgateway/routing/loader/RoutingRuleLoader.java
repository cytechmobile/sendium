package smsgateway.routing.loader;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import smsgateway.providers.LogProvider;
import smsgateway.routing.config.RoutingRule;

@ApplicationScoped
public class RoutingRuleLoader {
    private static final Logger LOGGER =
            LogProvider.getRoutingLogger(RoutingRuleLoader.class.getName());
    public static final String DEFAULT_RULE_GROUP_NAME = "default";

    @ConfigProperty(
            name = "sms.gateway.rules.dynamic.config.file.path",
            defaultValue = "conf/routing-rules.json")
    String configRulesPath;

    @Inject ObjectMapper objectMapper;

    private Map<String, List<RoutingRule>> ruleGroups = new HashMap<>();

    @PostConstruct
    void init() {
        loadRulesFromFile();
    }

    // Public method to allow reloading rules on demand
    public synchronized void reloadRules() {
        LOGGER.info("Reloading routing rules...");
        loadRulesFromFile();
    }

    // Method to parse and validate rules from a JSON string without persisting
    public boolean validateRules(Map<String, List<RoutingRule>> rules) {
        return rules != null
                && !rules.isEmpty()
                && rules.containsKey(DEFAULT_RULE_GROUP_NAME)
                && !rules.get(DEFAULT_RULE_GROUP_NAME).isEmpty();
    }

    private Path initializeRulesFile() throws IOException {
        Path rulesPath = Paths.get(configRulesPath);
        if (!Files.exists(rulesPath) || !Files.isReadable(rulesPath)) {
            LOGGER.info(
                    "Routing rules file not found or not readable at {}. Attempting to create the file.",
                    configRulesPath);
            try {
                Path parentDir = rulesPath.getParent();
                if (parentDir != null && !Files.exists(parentDir)) {
                    Files.createDirectories(parentDir);
                }
                // Initialize with a basic structure: {"rule_groups": {}} for consistency
                // or simply an empty object {} if that's preferred for a "completely empty" start.
                // Using {"rule_groups": {}} because the parser expects this structure.
                var defGroups = new HashMap<String, List<RoutingRule>>();
                var defRules = new ArrayList<RoutingRule>();
                var rule = new RoutingRule("initial", new RoutingRule.Conditions(), null, null);
                defRules.add(rule);
                defGroups.put(DEFAULT_RULE_GROUP_NAME, defRules);
                persistRules(defGroups);
                // concurrently
                LOGGER.info(
                        "Created empty routing rules file with default structure at {}",
                        configRulesPath);
            } catch (IOException e) {
                LOGGER.error("Could not create routing rules file at {}", configRulesPath, e);
                throw e; // Re-throw to be handled by the caller
            }
        }
        return rulesPath;
    }

    private Map<String, List<RoutingRule>> parseRulesFromStream(InputStream inputStream)
            throws IOException {
        if (inputStream.available() == 0) {
            LOGGER.info("routing rules file is empty. Initializing with empty rule groups map.");
            // Return a structure that represents no rule groups, consistent with what objectMapper
            // might return for "{}".
            // The TypeReference is Map<String, List<RoutingRule>>, so an empty map is appropriate.
            return new HashMap<>();
        }

        Map<String, List<RoutingRule>> parsedRuleGroups =
                objectMapper.readValue(
                        inputStream, new TypeReference<Map<String, List<RoutingRule>>>() {});

        if (parsedRuleGroups == null) { // Should ideally not happen with TypeReference
            LOGGER.warn("Parsing rules returned null, initializing with empty map.");
            return new HashMap<>();
        }

        // The JSON structure is expected to be a map where keys are group names
        // and values are lists of rules. If the top level is not a map,
        // or if it doesn't contain "rule_groups" as per some designs, this might need adjustment.
        // Current TypeReference Map<String, List<RoutingRule>> implies direct map of groups.

        int totalRules = 0;
        for (Map.Entry<String, List<RoutingRule>> entry : parsedRuleGroups.entrySet()) {
            List<RoutingRule> groupRules = entry.getValue();
            if (groupRules != null) {
                totalRules += groupRules.size();
            } else {
                // Replace null list with an empty list for consistency
                entry.setValue(new ArrayList<>());
                LOGGER.warn(
                        "Rule group '{}' had a null list of rules; replaced with empty list.",
                        entry.getKey());
            }
        }
        LOGGER.info(
                "Successfully parsed {} rule groups with a total of {} rules.",
                parsedRuleGroups.size(),
                totalRules);
        return parsedRuleGroups;
    }

    private synchronized void loadRulesFromFile() {
        Path rulesFilePath;
        try {
            rulesFilePath = initializeRulesFile();
        } catch (IOException e) {
            // If file initialization fails (e.g., cannot create it), load empty rules.
            LOGGER.error(
                    "Failed to initialize rules file at {}: {}. Loading empty rules.",
                    configRulesPath,
                    e.getMessage());
            this.ruleGroups = new HashMap<>();
            return;
        }

        try (InputStream inputStream = Files.newInputStream(rulesFilePath)) {
            this.ruleGroups = parseRulesFromStream(inputStream);
        } catch (IOException e) {
            LOGGER.error(
                    "Failed to load or parse routing rules file from {}. Loading empty rules.",
                    rulesFilePath,
                    e);
            this.ruleGroups = new HashMap<>(); // Initialize to empty on error
        }
    }

    public boolean persistRules() {
        return persistRules(this.ruleGroups);
    }

    public synchronized boolean persistRules(Map<String, List<RoutingRule>> rules) {
        if (!validateRules(rules)) {
            return false;
        }
        Path rulesPath = Paths.get(configRulesPath);
        try {
            Path parentDir = rulesPath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }
            var json =
                    objectMapper
                            .copy()
                            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsString(rules);
            Files.writeString(
                    rulesPath,
                    json,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            ruleGroups = rules;
            LOGGER.info(
                    "Successfully persisted {} rule groups to {}",
                    ruleGroups.size(),
                    configRulesPath);
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to persist rule groups to {}", configRulesPath, e);
            return false;
        }
    }

    public void setConfigRulesPath(String configRulesPath) {
        this.configRulesPath = configRulesPath;
    }

    public String getConfigRulesPath() {
        return configRulesPath;
    }

    public synchronized Map<String, List<RoutingRule>> getRuleGroups() {
        // Return a deep copy to prevent external modification of the lists within the map
        Map<String, List<RoutingRule>> copy = new HashMap<>();
        for (Map.Entry<String, List<RoutingRule>> entry : ruleGroups.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    public List<RoutingRule> getRulesForGroup(String groupName) {
        synchronized (this) { // Synchronize read access for consistency during modifications
            List<RoutingRule> rules = ruleGroups.get(groupName);
            if (rules == null) {
                return Collections.emptyList();
            }
            // Return a copy to prevent external modification
            return new ArrayList<>(rules);
        }
    }

    public synchronized boolean addRule(String groupName, RoutingRule rule) {
        if (!isValidGroupName(groupName) || rule == null || !isValidRuleName(rule.getRuleName())) {
            // Rule specific check for null rule object is kept here as isValidRuleName only checks
            // the name
            if (rule == null) LOGGER.warn("Rule object cannot be null for adding a rule.");
            return false;
        }
        List<RoutingRule> rules = ruleGroups.computeIfAbsent(groupName, k -> new ArrayList<>());

        if (rules.stream().anyMatch(r -> r.getRuleName().equals(rule.getRuleName()))) {
            LOGGER.warn(
                    "Rule with name '{}' already exists in group '{}'.",
                    rule.getRuleName(),
                    groupName);
            return false;
        }

        rules.add(rule);
        persistRules();
        LOGGER.info("Added rule '{}' to group '{}'.", rule.getRuleName(), groupName);
        return true;
    }

    public synchronized boolean updateRule(
            String groupName, String ruleName, RoutingRule updatedRule) {
        if (!isValidGroupName(groupName)
                || !isValidRuleName(ruleName)
                || updatedRule == null
                || !isValidRuleName(updatedRule.getRuleName())) {
            // Rule specific check for null updatedRule object is kept here
            if (updatedRule == null)
                LOGGER.warn("Updated rule object cannot be null for updating a rule.");
            return false;
        }
        List<RoutingRule> rules = ruleGroups.get(groupName);
        if (rules == null) {
            LOGGER.warn("Group '{}' not found for updating rule '{}'.", groupName, ruleName);
            return false;
        }

        Optional<RoutingRule> existingRuleOpt =
                rules.stream().filter(r -> r.getRuleName().equals(ruleName)).findFirst();

        if (existingRuleOpt.isPresent()) {
            RoutingRule existingRule = existingRuleOpt.get();

            // If rule name is changing, check for conflict with another existing rule in the same
            // group
            if (!ruleName.equals(updatedRule.getRuleName())
                    && rules.stream()
                            .anyMatch(r -> r.getRuleName().equals(updatedRule.getRuleName()))) {
                LOGGER.warn(
                        "Another rule with name '{}' already exists in group '{}'. Cannot update.",
                        updatedRule.getRuleName(),
                        groupName);
                return false;
            }

            existingRule.setRuleName(updatedRule.getRuleName());
            existingRule.setConditions(updatedRule.getConditions());

            persistRules();
            LOGGER.info(
                    "Updated rule '{}' (now '{}') in group '{}'.",
                    ruleName,
                    updatedRule.getRuleName(),
                    groupName);
            return true;
        } else {
            LOGGER.warn("Rule '{}' not found in group '{}' for update.", ruleName, groupName);
            return false;
        }
    }

    public synchronized boolean deleteRule(String groupName, String ruleName) {
        if (!isValidGroupName(groupName) || !isValidRuleName(ruleName)) {
            return false;
        }
        List<RoutingRule> rules = ruleGroups.get(groupName);
        if (rules == null) {
            LOGGER.warn("Group '{}' not found for deleting rule '{}'.", groupName, ruleName);
            return false;
        }
        boolean removed = rules.removeIf(rule -> rule.getRuleName().equals(ruleName));
        if (removed) {
            // If the group becomes empty after deleting the rule, we could optionally remove the
            // group.
            // Per instructions, keep the group even if it's empty.
            persistRules();
            LOGGER.info("Deleted rule '{}' from group '{}'.", ruleName, groupName);
        } else {
            LOGGER.info("Rule '{}' not found in group '{}' for deletion.", ruleName, groupName);
        }
        return removed;
    }

    public synchronized boolean createRuleGroup(String groupName) {
        if (!isValidGroupName(groupName)) {
            return false;
        }
        if (ruleGroups.containsKey(groupName)) {
            LOGGER.info("Rule group '{}' already exists.", groupName);
            return false;
        }
        ruleGroups.put(groupName, new ArrayList<>());
        persistRules();
        LOGGER.info("Created new rule group '{}'.", groupName);
        return true;
    }

    public synchronized boolean deleteRuleGroup(String groupName) {
        if (!isValidGroupName(groupName)) {
            return false;
        }
        if (DEFAULT_RULE_GROUP_NAME.equals(groupName)) {
            LOGGER.warn("Cannot delete the default rule group '{}'.", groupName);
            return false;
        }
        if (ruleGroups.remove(groupName) != null) {
            persistRules();
            LOGGER.info("Deleted rule group '{}'.", groupName);
            return true;
        } else {
            LOGGER.info("Rule group '{}' not found for deletion.", groupName);
            return false;
        }
    }

    public String getDefaultGroupName() {
        return DEFAULT_RULE_GROUP_NAME;
    }

    private boolean isValidGroupName(String groupName) {
        if (groupName == null || groupName.trim().isEmpty()) {
            LOGGER.warn("Group name cannot be null or empty.");
            return false;
        }
        return true;
    }

    private boolean isValidRuleName(String ruleName) {
        if (ruleName == null || ruleName.trim().isEmpty()) {
            LOGGER.warn("Rule name cannot be null or empty.");
            return false;
        }
        return true;
    }
}
