package smsgateway.routing.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.quarkus.test.junit.QuarkusTest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import smsgateway.dto.DlrForwardingPayload;
import smsgateway.dto.IncomingSms;
import smsgateway.routing.config.RoutingRule;
import smsgateway.routing.config.RoutingRule.Conditions;
import smsgateway.routing.destination.AbstractOutWorker;
import smsgateway.routing.loader.RoutingRuleLoader;
import smsgateway.services.DlrForwardingService;
import smsgateway.services.DlrMappingService;
import smsgateway.smpp.DynamicVendorConfigWatcher;
import smsgateway.smpp.SmppClientWorker;

@QuarkusTest
public class SmsRouterTest {

    @Mock private RoutingRuleLoader mockRuleLoader;

    @Mock private DynamicVendorConfigWatcher mockVendorConfigWatcher;

    @Mock private DlrMappingService mockDlrMappingService;

    @Mock private DlrForwardingService mockDlrForwardingService;

    @Mock private Logger mockLogger;

    // Instance under test
    private SmsRouter smsRouter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Initialize smsRouter with the constructor that takes the mocked Logger for most tests
        smsRouter = new SmsRouter(mockRuleLoader, mockVendorConfigWatcher, mockLogger);
    }

    // --- Tests for route method ---
    @Test
    void route_successfulHandling_shouldStoreAcceptedAndForwardDlr() {
        // Re-initialize smsRouter with DLR service mocks for route() method tests
        smsRouter =
                new SmsRouter(
                        mockRuleLoader,
                        mockVendorConfigWatcher,
                        mockDlrMappingService,
                        mockDlrForwardingService);

        IncomingSms sms = new IncomingSms();
        sms.setInternalId("testInternalId");
        sms.setFrom("sender");
        sms.setTo("receiver");
        sms.setText("hello");

        DlrForwardingPayload initialPayload = new DlrForwardingPayload();
        when(mockDlrMappingService.getDlrPayload("testInternalId")).thenReturn(initialPayload);

        String defaultGroupName = "default";
        when(mockRuleLoader.getDefaultGroupName()).thenReturn(defaultGroupName);

        RoutingRule matchingRule = new RoutingRule();
        matchingRule.setRuleName("MatchingRule");
        matchingRule.setConditions(new Conditions());
        matchingRule.getConditions().setSender("sender"); // Ensure conditions match
        matchingRule.setDestinationId("someDestination");

        when(mockRuleLoader.getRulesForGroup(defaultGroupName))
                .thenReturn(Collections.singletonList(matchingRule));

        SmppClientWorker mockSmppClientWorker = mock(SmppClientWorker.class);
        Map<String, AbstractOutWorker> smppWorkers = new HashMap<>();
        smppWorkers.put("someDestination", mockSmppClientWorker);
        when(mockVendorConfigWatcher.getWorkersActive()).thenReturn(smppWorkers);

        smsRouter.route(sms);

        verify(mockDlrMappingService, times(1))
                .storeDlrPayload(eq("testInternalId"), any(DlrForwardingPayload.class));
        verify(mockDlrForwardingService, never())
                .forwardDlr(any(DlrForwardingPayload.class), eq("-"));
    }

    @Test
    void route_unsuccessfulHandling_shouldStoreFailedAndForwardDlr() {
        smsRouter =
                new SmsRouter(
                        mockRuleLoader,
                        mockVendorConfigWatcher,
                        mockDlrMappingService,
                        mockDlrForwardingService);

        IncomingSms sms = new IncomingSms();
        sms.setInternalId("testInternalIdFailure");
        sms.setFrom("sender");
        sms.setTo("receiver");
        sms.setText("unhandled message");

        DlrForwardingPayload capturedPayload = new DlrForwardingPayload();
        when(mockDlrMappingService.getDlrPayload("testInternalIdFailure"))
                .thenReturn(capturedPayload);

        String defaultGroupName = "default";
        when(mockRuleLoader.getDefaultGroupName()).thenReturn(defaultGroupName);
        when(mockRuleLoader.getRulesForGroup(defaultGroupName)).thenReturn(Collections.emptyList());

        smsRouter.route(sms);

        org.mockito.ArgumentCaptor<DlrForwardingPayload> payloadCaptor =
                org.mockito.ArgumentCaptor.forClass(DlrForwardingPayload.class);
        verify(mockDlrMappingService, times(2))
                .storeDlrPayload(eq("testInternalIdFailure"), payloadCaptor.capture());

        List<DlrForwardingPayload> storedPayloads = payloadCaptor.getAllValues();
        assertThat(storedPayloads.get(0).getStatus()).isEqualTo("ACCEPTED");
        assertThat(storedPayloads.get(1).getStatus()).isEqualTo("FAILED");
        assertThat(storedPayloads.get(1).getErrorCode()).isEqualTo("999");

        verify(mockDlrForwardingService, times(1)).forwardDlr(payloadCaptor.capture(), eq("-"));
        assertThat(payloadCaptor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(payloadCaptor.getValue().getErrorCode()).isEqualTo("999");
    }

    // --- Tests for processMessageRecursive (package-private) ---
    @Test
    void processMessageRecursive_noRulesInGroup_shouldReturnFalse() {
        IncomingSms sms = new IncomingSms();
        String groupName = "testGroup";
        when(mockRuleLoader.getRulesForGroup(groupName)).thenReturn(Collections.emptyList());

        boolean result = smsRouter.processMessageRecursive(sms, groupName, new HashSet<>());
        assertThat(result).isFalse();
        verify(mockLogger).info("No rules found for group: {} or group not defined.", groupName);
    }

    @Test
    void processMessageRecursive_nullRulesForGroup_shouldReturnFalse() {
        IncomingSms sms = new IncomingSms();
        String groupName = "testGroup";
        when(mockRuleLoader.getRulesForGroup(groupName)).thenReturn(null);

        boolean result = smsRouter.processMessageRecursive(sms, groupName, new HashSet<>());
        assertThat(result).isFalse();
        verify(mockLogger).info("No rules found for group: {} or group not defined.", groupName);
    }

    @Test
    void processMessageRecursive_ruleMatch_dispatchToDestination() {
        IncomingSms sms = new IncomingSms();
        sms.setFrom("123");
        String groupName = "testGroup";
        String destinationId = "dest1";

        RoutingRule rule = new RoutingRule();
        rule.setRuleName("Rule1");
        Conditions conditions = new Conditions();
        conditions.setSender("123");
        rule.setConditions(conditions);
        rule.setDestinationId(destinationId);

        List<RoutingRule> rules = Collections.singletonList(rule);
        when(mockRuleLoader.getRulesForGroup(groupName)).thenReturn(rules);

        SmppClientWorker mockSmppClientWorker = mock(SmppClientWorker.class);
        Map<String, AbstractOutWorker> smppWorkers = new HashMap<>();
        smppWorkers.put(destinationId, mockSmppClientWorker);
        when(mockVendorConfigWatcher.getWorkersActive()).thenReturn(smppWorkers);

        boolean result = smsRouter.processMessageRecursive(sms, groupName, new HashSet<>());
        assertThat(result).isTrue();
        verify(mockSmppClientWorker).process(sms, "Rule1", destinationId);
        verify(mockLogger).info("Matched rule '{}' in group '{}'.", "Rule1", groupName);
        verify(mockLogger)
                .info("Executing destinationId: {} for rule '{}'.", destinationId, "Rule1");
    }

    @Test
    void processMessageRecursive_ruleMatch_noAction_shouldReturnTrue() {
        IncomingSms sms = new IncomingSms();
        sms.setFrom("123");
        String groupName = "testGroup";

        RoutingRule rule = new RoutingRule();
        rule.setRuleName("Rule1");
        Conditions conditions = new Conditions();
        conditions.setSender("123");
        rule.setConditions(conditions);

        List<RoutingRule> rules = Collections.singletonList(rule);
        when(mockRuleLoader.getRulesForGroup(groupName)).thenReturn(rules);

        boolean result = smsRouter.processMessageRecursive(sms, groupName, new HashSet<>());
        assertThat(result).isTrue();
        verify(mockLogger)
                .warn(
                        "Rule '{}' in group '{}' matched but has no action (no nextRuleGroupName or destinationId). Rule considered handled (matched).",
                        "Rule1",
                        groupName);
    }

    @Test
    void processMessageRecursive_noRuleMatchInGroup_shouldReturnFalse() {
        IncomingSms sms = new IncomingSms();
        sms.setFrom("non_matching_sender");
        String groupName = "testGroup";

        RoutingRule rule = new RoutingRule();
        rule.setRuleName("Rule1");
        Conditions conditions = new Conditions();
        conditions.setSender("123");
        rule.setConditions(conditions);

        List<RoutingRule> rules = Collections.singletonList(rule);
        when(mockRuleLoader.getRulesForGroup(groupName)).thenReturn(rules);

        boolean result = smsRouter.processMessageRecursive(sms, groupName, new HashSet<>());
        assertThat(result).isFalse();
        verify(mockLogger).info("No rule matched in group: {}", groupName);
    }

    @Test
    void processMessageRecursive_chainToNextGroup_handlesMessage() {
        IncomingSms sms = new IncomingSms();
        sms.setFrom("123");
        String groupName1 = "group1";
        String groupName2 = "group2";
        String destinationId = "dest_in_group2";

        RoutingRule rule1 = new RoutingRule();
        rule1.setRuleName("Rule_G1");
        Conditions conditions1 = new Conditions();
        conditions1.setSender("123");
        rule1.setConditions(conditions1);
        rule1.setNextRuleGroupName(groupName2);

        RoutingRule rule2 = new RoutingRule();
        rule2.setRuleName("Rule_G2");
        Conditions conditions2 = new Conditions();
        conditions2.setSender("123");
        rule2.setConditions(conditions2);
        rule2.setDestinationId(destinationId);

        when(mockRuleLoader.getRulesForGroup(groupName1))
                .thenReturn(Collections.singletonList(rule1));
        when(mockRuleLoader.getRulesForGroup(groupName2))
                .thenReturn(Collections.singletonList(rule2));

        SmppClientWorker mockSmppClientWorkerInGroup2 = mock(SmppClientWorker.class);
        Map<String, AbstractOutWorker> smppWorkersG2 = new HashMap<>();
        smppWorkersG2.put(destinationId, mockSmppClientWorkerInGroup2);
        when(mockVendorConfigWatcher.getWorkersActive()).thenReturn(smppWorkersG2);

        boolean result = smsRouter.processMessageRecursive(sms, groupName1, new HashSet<>());
        assertThat(result).isTrue();
        verify(mockSmppClientWorkerInGroup2).process(sms, "Rule_G2", destinationId);
        verify(mockLogger).info("Chaining to rule group: {}", groupName2);
    }

    @Test
    void
            processMessageRecursive_chainToNextGroup_doesNotHandleMessage_currentRuleNoDest_continues() {
        IncomingSms sms = new IncomingSms();
        sms.setFrom("123");
        String groupName1 = "group1";
        String groupName2 = "group2_no_match";
        String finalDestinationId = "final_dest";

        RoutingRule rule1_g1 = new RoutingRule();
        rule1_g1.setRuleName("Rule1_G1_ChainToG2");
        Conditions conditions1_g1 = new Conditions();
        conditions1_g1.setSender("123");
        rule1_g1.setConditions(conditions1_g1);
        rule1_g1.setNextRuleGroupName(groupName2);

        RoutingRule rule2_g1 = new RoutingRule();
        rule2_g1.setRuleName("Rule2_G1_DirectToDest");
        Conditions conditions2_g1 = new Conditions();
        conditions2_g1.setSender("123");
        rule2_g1.setConditions(conditions2_g1);
        rule2_g1.setDestinationId(finalDestinationId);

        List<RoutingRule> rulesForGroup1 = new ArrayList<>();
        rulesForGroup1.add(rule1_g1);
        rulesForGroup1.add(rule2_g1);

        RoutingRule rule_g2_no_match = new RoutingRule();
        rule_g2_no_match.setRuleName("Rule_G2_NoMatch");
        Conditions conditions_g2 = new Conditions();
        conditions_g2.setSender("non_matching_sender_for_g2");
        rule_g2_no_match.setConditions(conditions_g2);

        when(mockRuleLoader.getRulesForGroup(groupName1)).thenReturn(rulesForGroup1);
        when(mockRuleLoader.getRulesForGroup(groupName2))
                .thenReturn(Collections.singletonList(rule_g2_no_match));

        SmppClientWorker mockFinalSmppClientWorker = mock(SmppClientWorker.class);
        Map<String, AbstractOutWorker> finalSmppWorkers = new HashMap<>();
        finalSmppWorkers.put(finalDestinationId, mockFinalSmppClientWorker);
        when(mockVendorConfigWatcher.getWorkersActive()).thenReturn(finalSmppWorkers);

        boolean result = smsRouter.processMessageRecursive(sms, groupName1, new HashSet<>());
        assertThat(result).isTrue();

        verify(mockLogger).info("Chaining to rule group: {}", groupName2);
        verify(mockLogger).info("No rule matched in group: {}", groupName2);
        verify(mockLogger)
                .info(
                        "Chain from rule '{}' to group '{}' did not handle the message. Checking current rule's destinationId.",
                        rule1_g1.getRuleName(),
                        rule1_g1.getNextRuleGroupName());
        verify(mockFinalSmppClientWorker).process(sms, rule2_g1.getRuleName(), finalDestinationId);
    }

    @Test
    void processMessageRecursive_loopDetection() {
        IncomingSms sms = new IncomingSms();
        String groupName = "groupLoop";

        RoutingRule rule = new RoutingRule();
        rule.setRuleName("LoopRule");
        rule.setConditions(new Conditions());
        rule.setNextRuleGroupName(groupName);

        List<RoutingRule> rules = Collections.singletonList(rule);
        // This 'when' call uses a raw value for groupName, which is fine.
        when(mockRuleLoader.getRulesForGroup(groupName)).thenReturn(rules);

        boolean result = smsRouter.processMessageRecursive(sms, groupName, new HashSet<>());

        assertThat(result).isFalse();
        // The problematic verify call:
        verify(mockLogger)
                .error(
                        eq("Loop detected: trying to re-process rule group: {}. Path: {}"),
                        eq(groupName),
                        anySet());
    }

    @Test
    void
            processMessageRecursive_ruleMatches_destinationHandlerMissing_shouldReturnTrueAndLogWarning() {
        IncomingSms sms = new IncomingSms();
        sms.setFrom("123");
        String groupName = "testGroup";
        String destinationId = "missingDest";

        RoutingRule rule = new RoutingRule();
        rule.setRuleName("RuleWithMissingHandler");
        Conditions conditions = new Conditions();
        conditions.setSender("123");
        rule.setConditions(conditions);
        rule.setDestinationId(destinationId);

        List<RoutingRule> rules = Collections.singletonList(rule);
        when(mockRuleLoader.getRulesForGroup(groupName)).thenReturn(rules);

        when(mockVendorConfigWatcher.getWorkersActive()).thenReturn(new HashMap<>());

        boolean result = smsRouter.processMessageRecursive(sms, groupName, new HashSet<>());
        assertThat(result).isTrue();
        verify(mockLogger)
                .warn(
                        "No handler found for destinationId: {} for rule '{}'. Rule considered handled (matched).",
                        destinationId,
                        "RuleWithMissingHandler");
    }

    // --- Tests for conditionsMatch (package-private) ---
    @Test
    void conditionsMatch_nullConditions() {
        IncomingSms sms = new IncomingSms();
        assertThat(smsRouter.conditionsMatch(sms, null)).isTrue();
    }

    @Test
    void conditionsMatch_emptyConditions() {
        IncomingSms sms = new IncomingSms();
        Conditions conditions = new Conditions();
        assertThat(smsRouter.conditionsMatch(sms, conditions)).isTrue();
    }

    @Test
    void conditionsMatch_senderMatch_exact() {
        IncomingSms sms = new IncomingSms();
        sms.setFrom("12345");
        Conditions conditions = new Conditions();
        conditions.setSender("12345");
        assertThat(smsRouter.conditionsMatch(sms, conditions)).isTrue();
    }

    @Test
    void conditionsMatch_senderMatch_prefix() {
        IncomingSms sms = new IncomingSms();
        sms.setFrom("123456789");
        Conditions conditions = new Conditions();
        conditions.setSender("12345*");
        assertThat(smsRouter.conditionsMatch(sms, conditions)).isTrue();
    }

    @Test
    void conditionsMatch_senderNoMatch() {
        IncomingSms sms = new IncomingSms();
        sms.setFrom("54321");
        Conditions conditions = new Conditions();
        conditions.setSender("12345");
        assertThat(smsRouter.conditionsMatch(sms, conditions)).isFalse();
    }

    @Test
    void conditionsMatch_senderNoMatch_prefix() {
        IncomingSms sms = new IncomingSms();
        sms.setFrom("54321");
        Conditions conditions = new Conditions();
        conditions.setSender("12345*");
        assertThat(smsRouter.conditionsMatch(sms, conditions)).isFalse();
    }

    @Test
    void conditionsMatch_nullSenderInSms_noMatch() {
        IncomingSms sms = new IncomingSms();
        Conditions conditions = new Conditions();
        conditions.setSender("12345");
        assertThat(smsRouter.conditionsMatch(sms, conditions)).isFalse();
    }

    @Test
    void conditionsMatch_recipientMatch_exact() {
        IncomingSms sms = new IncomingSms();
        sms.setTo("67890");
        Conditions conditions = new Conditions();
        conditions.setRecipient("67890");
        assertThat(smsRouter.conditionsMatch(sms, conditions)).isTrue();
    }

    @Test
    void conditionsMatch_recipientMatch_prefix() {
        IncomingSms sms = new IncomingSms();
        sms.setTo("678901234");
        Conditions conditions = new Conditions();
        conditions.setRecipient("67890*");
        assertThat(smsRouter.conditionsMatch(sms, conditions)).isTrue();
    }

    @Test
    void conditionsMatch_recipientNoMatch() {
        IncomingSms sms = new IncomingSms();
        sms.setTo("09876");
        Conditions conditions = new Conditions();
        conditions.setRecipient("67890");
        assertThat(smsRouter.conditionsMatch(sms, conditions)).isFalse();
    }

    @Test
    void conditionsMatch_recipientNoMatch_prefix() {
        IncomingSms sms = new IncomingSms();
        sms.setTo("09876");
        Conditions conditions = new Conditions();
        conditions.setRecipient("67890*");
        assertThat(smsRouter.conditionsMatch(sms, conditions)).isFalse();
    }

    @Test
    void conditionsMatch_nullRecipientInSms_noMatch() {
        IncomingSms sms = new IncomingSms();
        Conditions conditions = new Conditions();
        conditions.setRecipient("67890");
        assertThat(smsRouter.conditionsMatch(sms, conditions)).isFalse();
    }

    @Test
    void conditionsMatch_textContains_match() {
        IncomingSms sms = new IncomingSms();
        sms.setText("Hello world");
        Conditions conditions = new Conditions();
        conditions.setTextContains("world");
        assertThat(smsRouter.conditionsMatch(sms, conditions)).isTrue();
    }

    @Test
    void conditionsMatch_textContains_noMatch() {
        IncomingSms sms = new IncomingSms();
        sms.setText("Hello world");
        Conditions conditions = new Conditions();
        conditions.setTextContains("foo");
        assertThat(smsRouter.conditionsMatch(sms, conditions)).isFalse();
    }

    @Test
    void conditionsMatch_nullTextInSms_textContains_noMatch() {
        IncomingSms sms = new IncomingSms();
        Conditions conditions = new Conditions();
        conditions.setTextContains("world");
        assertThat(smsRouter.conditionsMatch(sms, conditions)).isFalse();
    }

    @Test
    void conditionsMatch_textMatchesRegex_match() {
        IncomingSms sms = new IncomingSms();
        sms.setText("abc123xyz");
        Conditions conditions = new Conditions();
        conditions.setTextMatchesRegex("^[a-z]{3}[0-9]{3}[a-z]{3}$");
        assertThat(smsRouter.conditionsMatch(sms, conditions)).isTrue();
    }

    @Test
    void conditionsMatch_textMatchesRegex_noMatch() {
        IncomingSms sms = new IncomingSms();
        sms.setText("abc12xyz");
        Conditions conditions = new Conditions();
        conditions.setTextMatchesRegex("^[a-z]{3}[0-9]{3}[a-z]{3}$");
        assertThat(smsRouter.conditionsMatch(sms, conditions)).isFalse();
    }

    @Test
    void conditionsMatch_nullTextInSms_textMatchesRegex_noMatch() {
        IncomingSms sms = new IncomingSms();
        Conditions conditions = new Conditions();
        conditions.setTextMatchesRegex("^[a-z]{3}[0-9]{3}[a-z]{3}$");
        assertThat(smsRouter.conditionsMatch(sms, conditions)).isFalse();
    }

    @Test
    void conditionsMatch_allConditionsMatch() {
        IncomingSms sms = new IncomingSms();
        sms.setFrom("12345");
        sms.setTo("67890");
        sms.setText("Hello world, this is a test.");
        Conditions conditions = new Conditions();
        conditions.setSender("123*");
        conditions.setRecipient("678*");
        conditions.setTextContains("world");
        conditions.setTextMatchesRegex("^Hello.*test.$");
        assertThat(smsRouter.conditionsMatch(sms, conditions)).isTrue();
    }

    @Test
    void conditionsMatch_oneConditionFails_sender() {
        IncomingSms sms = new IncomingSms();
        sms.setFrom("wrong_sender");
        sms.setTo("67890");
        sms.setText("Hello world, this is a test.");
        Conditions conditions = new Conditions();
        conditions.setSender("123*");
        conditions.setRecipient("678*");
        conditions.setTextContains("world");
        conditions.setTextMatchesRegex("^Hello.*test.$");
        assertThat(smsRouter.conditionsMatch(sms, conditions)).isFalse();
    }

    @Test
    void conditionsMatch_oneConditionFails_recipient() {
        IncomingSms sms = new IncomingSms();
        sms.setFrom("12345");
        sms.setTo("wrong_recipient");
        sms.setText("Hello world, this is a test.");
        Conditions conditions = new Conditions();
        conditions.setSender("123*");
        conditions.setRecipient("678*");
        conditions.setTextContains("world");
        conditions.setTextMatchesRegex("^Hello.*test.$");
        assertThat(smsRouter.conditionsMatch(sms, conditions)).isFalse();
    }

    @Test
    void conditionsMatch_oneConditionFails_textContains() {
        IncomingSms sms = new IncomingSms();
        sms.setFrom("12345");
        sms.setTo("67890");
        sms.setText("Hello there, this is a test.");
        Conditions conditions = new Conditions();
        conditions.setSender("123*");
        conditions.setRecipient("678*");
        conditions.setTextContains("world");
        conditions.setTextMatchesRegex("^Hello.*test.$");
        assertThat(smsRouter.conditionsMatch(sms, conditions)).isFalse();
    }

    @Test
    void conditionsMatch_oneConditionFails_textRegex() {
        IncomingSms sms = new IncomingSms();
        sms.setFrom("12345");
        sms.setTo("67890");
        sms.setText("Hello world, this is not a test");
        Conditions conditions = new Conditions();
        conditions.setSender("123*");
        conditions.setRecipient("678*");
        conditions.setTextContains("world");
        conditions.setTextMatchesRegex("^Hello.*test.$");
        assertThat(smsRouter.conditionsMatch(sms, conditions)).isFalse();
    }
}
