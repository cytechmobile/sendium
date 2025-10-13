package smsgateway.routing.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import smsgateway.routing.config.RoutingRule;

@QuarkusTest
class RoutingRuleLoaderTest {

    @ConfigProperty(
            name = "sms.gateway.rules.dynamic.config.file.path",
            defaultValue = "conf/routing-rules.json")
    String configRulesPath;

    @Inject ObjectMapper objectMapper;
    @Inject RoutingRuleLoader ruleLoader;

    private Path testRulesFilePath;

    @BeforeEach
    void setUp() throws Exception {
        testRulesFilePath = Paths.get("./target/test-rules.json");
        ruleLoader.setConfigRulesPath(testRulesFilePath.toString());
        ruleLoader.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        ruleLoader.setConfigRulesPath(configRulesPath);
        ruleLoader.init();
        Files.deleteIfExists(testRulesFilePath);
    }

    @Test
    void init_nonExistentFile_shouldCreateEmptyFileAndLoadEmptyRules() throws IOException {

        assertTrue(
                Files.exists(testRulesFilePath),
                "routing-rules.json should be created by init if non-existent.");
        assertThat(Files.readString(testRulesFilePath))
                .isNotBlank()
                .contains("default")
                .contains("initial");
        assertThat(ruleLoader.getRuleGroups())
                .isNotEmpty()
                .containsKey(RoutingRuleLoader.DEFAULT_RULE_GROUP_NAME);
    }

    @Test
    void init_emptyFile_shouldLoadEmptyRules() throws IOException {
        Files.writeString(testRulesFilePath, "{}"); // Create an empty JSON file
        ruleLoader.setConfigRulesPath(testRulesFilePath.toString());
        ruleLoader.init(); // Re-initialize to load the new empty file
        assertTrue(
                ruleLoader.getRuleGroups().isEmpty(),
                "Rule groups should be empty when loaded from an empty file.");
    }

    @Test
    void init_validFile_shouldLoadRulesInOrder() throws IOException {
        String jsonContent =
                """
        {
          "default": [
            { "ruleName": "RuleA", "conditions": {}, "destinationId": "LOG_A" },
            { "ruleName": "RuleB", "conditions": {}, "destinationId": "LOG_B" }
          ],
          "group1": [
            { "ruleName": "RuleC", "conditions": {}, "destinationId": "LOG_C" },
            { "ruleName": "RuleD", "conditions": {}, "destinationId": "LOG_D" }
          ]
        }
        """;
        Files.writeString(testRulesFilePath, jsonContent);
        ruleLoader.setConfigRulesPath(testRulesFilePath.toString());
        ruleLoader.init(); // Re-initialize

        Map<String, List<RoutingRule>> groups = ruleLoader.getRuleGroups();
        assertFalse(groups.isEmpty(), "Rule groups should not be empty.");
        assertEquals(2, groups.size(), "Should load two rule groups.");

        assertTrue(groups.containsKey("default"), "Should contain 'default' group.");
        List<RoutingRule> defaultRules = groups.get("default");
        assertEquals(2, defaultRules.size());
        assertEquals(
                "RuleA",
                defaultRules.get(0).getRuleName(),
                "RuleA should be first as per file order.");
        assertEquals(
                "RuleB",
                defaultRules.get(1).getRuleName(),
                "RuleB should be second as per file order.");

        assertTrue(groups.containsKey("group1"), "Should contain 'group1' group.");
        List<RoutingRule> group1Rules = groups.get("group1");
        assertEquals(2, group1Rules.size());
        assertEquals(
                "RuleC",
                group1Rules.get(0).getRuleName(),
                "RuleC should be first as per file order.");
        assertEquals(
                "RuleD",
                group1Rules.get(1).getRuleName(),
                "RuleD should be second as per file order.");
    }

    @Test
    void init_malformedFile_shouldLoadEmptyRulesAndLogError() throws IOException {
        Files.writeString(
                testRulesFilePath,
                "{ \"default\": [ {\"ruleName\": \"TestRule\""); // Malformed JSON

        // Suppress stderr for this test to avoid polluting test output with expected error
        // This is tricky without a logging framework. For now, we'll just check the outcome.
        ruleLoader.setConfigRulesPath(testRulesFilePath.toString());
        ruleLoader.init(); // Re-initialize

        assertTrue(
                ruleLoader.getRuleGroups().isEmpty(),
                "Rule groups should be empty after loading a malformed file.");
        // Ideally, also check that an error was logged. This might require a mock logger or
        // capturing System.err.
    }

    @Test
    void createRuleGroup_newGroup_shouldSucceedAndPersist() throws IOException {
        assertTrue(
                ruleLoader.createRuleGroup("newGroup"),
                "Should return true for new group creation.");
        assertTrue(
                ruleLoader.getRuleGroups().containsKey("newGroup"),
                "New group should be in the map.");

        Map<String, List<RoutingRule>> persistedGroups = readRulesFromFile();
        assertTrue(
                persistedGroups.containsKey("newGroup"), "New group should be persisted to file.");
    }

    @Test
    void createRuleGroup_existingGroup_shouldFail() {
        ruleLoader.createRuleGroup("existingGroup"); // Create it once
        assertFalse(
                ruleLoader.createRuleGroup("existingGroup"),
                "Should return false for existing group.");
    }

    @Test
    void createRuleGroup_nullOrEmptyName_shouldFail() {
        assertFalse(ruleLoader.createRuleGroup(null), "Should return false for null group name.");
        assertFalse(
                ruleLoader.createRuleGroup("  "),
                "Should return false for empty/blank group name.");
    }

    @Test
    void deleteRuleGroup_existingEmptyGroup_shouldSucceedAndPersist() throws IOException {
        ruleLoader.createRuleGroup("emptyGroup");
        assertTrue(
                ruleLoader.deleteRuleGroup("emptyGroup"),
                "Should return true for deleting empty group.");
        assertFalse(
                ruleLoader.getRuleGroups().containsKey("emptyGroup"),
                "Group should be removed from map.");

        Map<String, List<RoutingRule>> persistedGroups = readRulesFromFile();
        assertFalse(
                persistedGroups.containsKey("emptyGroup"),
                "Group should be removed from persisted file.");
    }

    @Test
    void deleteRuleGroup_existingGroupWithRules_shouldSucceedAndPersist() throws IOException {
        ruleLoader.createRuleGroup("groupWithRules");
        ruleLoader.addRule("groupWithRules", createSampleRule("rule1", "dest1"));

        assertTrue(
                ruleLoader.deleteRuleGroup("groupWithRules"),
                "Should return true for deleting group with rules.");
        assertFalse(
                ruleLoader.getRuleGroups().containsKey("groupWithRules"),
                "Group should be removed from map.");

        Map<String, List<RoutingRule>> persistedGroups = readRulesFromFile();
        assertFalse(
                persistedGroups.containsKey("groupWithRules"),
                "Group should be removed from persisted file.");
    }

    @Test
    void deleteRuleGroup_defaultGroup_shouldFail() {
        // Ensure default group exists if loader creates it by default, or create it
        ruleLoader.createRuleGroup(RoutingRuleLoader.DEFAULT_RULE_GROUP_NAME);
        assertFalse(
                ruleLoader.deleteRuleGroup(RoutingRuleLoader.DEFAULT_RULE_GROUP_NAME),
                "Should not be able to delete default group.");
        assertTrue(
                ruleLoader.getRuleGroups().containsKey(RoutingRuleLoader.DEFAULT_RULE_GROUP_NAME),
                "Default group should still exist.");
    }

    @Test
    void deleteRuleGroup_nonExistentGroup_shouldFail() {
        assertFalse(
                ruleLoader.deleteRuleGroup("nonExistentGroup"),
                "Should return false for non-existent group.");
    }

    @Test
    void getRulesForGroup_existingGroup_shouldReturnRulesInOrder() {
        ruleLoader.createRuleGroup("testGroup");
        RoutingRule rule1 = createSampleRule("rule1", "dest1");
        RoutingRule rule2 = createSampleRule("rule2", "dest2");
        // Rules are added and should maintain their insertion order
        ruleLoader.addRule("testGroup", rule1);
        ruleLoader.addRule("testGroup", rule2);

        List<RoutingRule> rules = ruleLoader.getRulesForGroup("testGroup");
        assertEquals(2, rules.size());
        assertEquals("rule1", rules.get(0).getRuleName(), "Rule1 should be first.");
        assertEquals("rule2", rules.get(1).getRuleName(), "Rule2 should be second.");
    }

    @Test
    void getRulesForGroup_nonExistentGroup_shouldReturnEmptyList() {
        assertTrue(
                ruleLoader.getRulesForGroup("noSuchGroup").isEmpty(),
                "Should return empty list for non-existent group.");
    }

    @Test
    void addRule_toExistingGroup_shouldSucceedAndPersistInOrder() throws IOException {
        ruleLoader.createRuleGroup("targetGroup");
        RoutingRule ruleA = createSampleRule("ruleA", "destA");
        RoutingRule ruleB = createSampleRule("ruleB", "destB");

        assertTrue(ruleLoader.addRule("targetGroup", ruleA), "Adding ruleA should succeed.");
        assertTrue(ruleLoader.addRule("targetGroup", ruleB), "Adding ruleB should succeed.");

        List<RoutingRule> rules = ruleLoader.getRulesForGroup("targetGroup");
        assertEquals(2, rules.size());
        assertEquals("ruleA", rules.get(0).getRuleName(), "Rules should be in insertion order.");
        assertEquals("ruleB", rules.get(1).getRuleName());

        Map<String, List<RoutingRule>> persistedRules = readRulesFromFile();
        assertEquals(2, persistedRules.get("targetGroup").size());
        assertEquals("ruleA", persistedRules.get("targetGroup").get(0).getRuleName());
        assertEquals("ruleB", persistedRules.get("targetGroup").get(1).getRuleName());
    }

    @Test
    void addRule_createsNewGroupIfNotExists_shouldSucceedAndPersist() throws IOException {
        RoutingRule rule = createSampleRule("ruleNewGroup", "dest");
        assertTrue(
                ruleLoader.addRule("autoCreatedGroup", rule), "Adding rule should create group.");
        assertTrue(
                ruleLoader.getRuleGroups().containsKey("autoCreatedGroup"),
                "Group should be auto-created.");
        assertEquals(1, ruleLoader.getRulesForGroup("autoCreatedGroup").size());

        Map<String, List<RoutingRule>> persistedRules = readRulesFromFile();
        assertTrue(persistedRules.containsKey("autoCreatedGroup"));
        assertEquals(1, persistedRules.get("autoCreatedGroup").size());
    }

    @Test
    void addRule_duplicateRuleNameInSameGroup_shouldFail() {
        ruleLoader.createRuleGroup("groupForDuplicates");
        RoutingRule rule1 = createSampleRule("duplicateNameRule", "dest1");
        RoutingRule rule2 = createSampleRule("duplicateNameRule", "dest2"); // Same name

        assertTrue(
                ruleLoader.addRule("groupForDuplicates", rule1),
                "Adding first rule should succeed.");
        assertFalse(
                ruleLoader.addRule("groupForDuplicates", rule2),
                "Adding rule with duplicate name should fail.");
        assertEquals(
                1,
                ruleLoader.getRulesForGroup("groupForDuplicates").size(),
                "Only one rule should be present.");
    }

    @Test
    void addRule_nullRuleOrRuleName_shouldFail() {
        ruleLoader.createRuleGroup("testGroup");
        assertFalse(ruleLoader.addRule("testGroup", null), "Adding null rule should fail.");

        RoutingRule ruleNoName = new RoutingRule();
        ruleNoName.setConditions(new RoutingRule.Conditions());
        assertFalse(
                ruleLoader.addRule("testGroup", ruleNoName),
                "Adding rule with null name should fail.");

        ruleNoName.setRuleName("  "); // Blank name
        assertFalse(
                ruleLoader.addRule("testGroup", ruleNoName),
                "Adding rule with blank name should fail.");
    }

    @Test
    void updateRule_changeRuleName_shouldSucceedAndPersist() throws IOException {
        String group = "updateNameGroup";
        String oldName = "oldName";
        String newName = "newName";
        ruleLoader.createRuleGroup(group);
        ruleLoader.addRule(group, createSampleRule(oldName, "dest"));

        RoutingRule ruleWithNewName = createSampleRule(newName, "dest");
        assertTrue(
                ruleLoader.updateRule(group, oldName, ruleWithNewName),
                "Updating rule name should succeed.");

        List<RoutingRule> rules = ruleLoader.getRulesForGroup(group);
        assertEquals(1, rules.size());
        assertEquals(newName, rules.get(0).getRuleName());

        Map<String, List<RoutingRule>> persistedRules = readRulesFromFile();
        assertEquals(newName, persistedRules.get(group).get(0).getRuleName());
    }

    @Test
    void updateRule_changeNameToExisting_shouldFail() {
        String group = "updateConflictGroup";
        ruleLoader.createRuleGroup(group);
        ruleLoader.addRule(group, createSampleRule("rule1", "dest1"));
        ruleLoader.addRule(group, createSampleRule("rule2", "dest2"));

        RoutingRule updatedRule2AsRule1 =
                createSampleRule("rule1", "dest3"); // Attempt to rename rule2 to rule1

        assertFalse(
                ruleLoader.updateRule(group, "rule2", updatedRule2AsRule1),
                "Updating rule to an existing name should fail.");

        // Verify rule2 is still rule2 (and rule1 is still rule1)
        List<RoutingRule> rules = ruleLoader.getRulesForGroup(group);
        assertEquals("rule1", rules.get(0).getRuleName());
        assertEquals("rule2", rules.get(1).getRuleName());
    }

    @Test
    void updateRule_nonExistentRule_shouldFail() {
        ruleLoader.createRuleGroup("groupForUpdateFail");
        RoutingRule rule = createSampleRule("ruleX", "dest");
        assertFalse(ruleLoader.updateRule("groupForUpdateFail", "nonExistentRuleName", rule));
    }

    @Test
    void updateRule_inNonExistentGroup_shouldFail() {
        RoutingRule rule = createSampleRule("ruleY", "dest");
        assertFalse(ruleLoader.updateRule("nonExistentGroup", "ruleY", rule));
    }

    @Test
    void updateRule_nullOrInvalidUpdatedRule_shouldFail() {
        String group = "updateInvalidRuleGroup";
        String ruleName = "aRule";
        ruleLoader.createRuleGroup(group);
        ruleLoader.addRule(group, createSampleRule(ruleName, "dest"));

        assertFalse(
                ruleLoader.updateRule(group, ruleName, null),
                "Updating with null rule should fail.");

        RoutingRule ruleNoName = createSampleRule(null, "dest");
        assertFalse(
                ruleLoader.updateRule(group, ruleName, ruleNoName),
                "Updating with rule having null name should fail.");

        RoutingRule ruleBlankName = createSampleRule("  ", "dest");
        assertFalse(
                ruleLoader.updateRule(group, ruleName, ruleBlankName),
                "Updating with rule having blank name should fail.");
    }

    @Test
    void deleteRule_existingRule_shouldSucceedAndPersist() throws IOException {
        String group = "deleteRuleGroup";
        String ruleName = "ruleToDelete";
        ruleLoader.createRuleGroup(group);
        ruleLoader.addRule(group, createSampleRule(ruleName, "dest"));

        assertEquals(1, ruleLoader.getRulesForGroup(group).size());
        assertTrue(
                ruleLoader.deleteRule(group, ruleName), "Deleting existing rule should succeed.");
        assertTrue(
                ruleLoader.getRulesForGroup(group).isEmpty(),
                "Rule list should be empty after deletion.");

        Map<String, List<RoutingRule>> persistedRules = readRulesFromFile();
        assertTrue(persistedRules.get(group).isEmpty());
    }

    @Test
    void deleteRule_nonExistentRule_shouldFail() {
        ruleLoader.createRuleGroup("groupForDeleteFail");
        assertFalse(ruleLoader.deleteRule("groupForDeleteFail", "noSuchRule"));
    }

    @Test
    void deleteRule_fromNonExistentGroup_shouldFail() {
        assertFalse(ruleLoader.deleteRule("noSuchGroupForRuleDeletion", "anyRule"));
    }

    @Test
    void deleteRule_nullOrEmptyNames_shouldFail() {
        ruleLoader.createRuleGroup("testGroup");
        ruleLoader.addRule("testGroup", createSampleRule("aRule", "dest"));
        assertFalse(ruleLoader.deleteRule(null, "aRule"));
        assertFalse(ruleLoader.deleteRule("  ", "aRule"));
        assertFalse(ruleLoader.deleteRule("testGroup", null));
        assertFalse(ruleLoader.deleteRule("testGroup", "  "));
    }

    @Test
    void testRulesAreProcessedInOrderFromFile() throws IOException {
        String jsonContent =
                """
        {
          "orderedGroup": [
            { "ruleName": "FirstRule", "conditions": {}, "destinationId": "DEST_1" },
            { "ruleName": "SecondRule", "conditions": {}, "destinationId": "DEST_2" },
            { "ruleName": "ThirdRule", "conditions": {}, "destinationId": "DEST_3" }
          ]
        }
        """;
        Files.writeString(testRulesFilePath, jsonContent);
        ruleLoader.setConfigRulesPath(testRulesFilePath.toString());
        ruleLoader.init(); // Re-initialize to load the test file

        Map<String, List<RoutingRule>> groups = ruleLoader.getRuleGroups();
        assertTrue(groups.containsKey("orderedGroup"), "Should contain 'orderedGroup'.");

        List<RoutingRule> orderedRules = groups.get("orderedGroup");
        assertEquals(3, orderedRules.size(), "Should load three rules in 'orderedGroup'.");
        assertEquals("FirstRule", orderedRules.get(0).getRuleName(), "FirstRule should be first.");
        assertEquals(
                "SecondRule", orderedRules.get(1).getRuleName(), "SecondRule should be second.");
        assertEquals("ThirdRule", orderedRules.get(2).getRuleName(), "ThirdRule should be third.");
    }

    private Map<String, List<RoutingRule>> readRulesFromFile() throws IOException {
        if (!Files.exists(testRulesFilePath) || Files.size(testRulesFilePath) == 0) {
            return new HashMap<>();
        }
        return objectMapper.readValue(testRulesFilePath.toFile(), new TypeReference<>() {});
    }

    private RoutingRule createSampleRule(String name, String destId) {
        RoutingRule rule = new RoutingRule();
        rule.setRuleName(name);
        rule.setDestinationId(destId);
        rule.setConditions(new RoutingRule.Conditions());
        return rule;
    }
}
