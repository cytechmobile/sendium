package smsgateway.integration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import com.cloudhopper.smpp.pdu.SubmitSm;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import smsgateway.auth.ApiKeyFilter;
import smsgateway.auth.ApiKeyService;
import smsgateway.dto.DlrForwardingPayload;
import smsgateway.dto.IncomingSms;
import smsgateway.routing.config.RoutingRule;
import smsgateway.routing.loader.RoutingRuleLoader;
import smsgateway.services.DlrMappingService;
import smsgateway.smpp.DynamicVendorConfigWatcher;
import smsgateway.smpp.VendorConf;

@QuarkusTest
@QuarkusTestResource(MockSmppServerResource.class) // Use our mock server
public class DlrLifecycleTest {

    private static final String STATUS_ACCEPTED = "ACCEPTED";
    private static final String STATUS_SENT = "SENT";
    private static final String STATUS_DELIVERED = "DELIVRD";

    @Inject DlrMappingService dlrMappingService;
    @Inject ApiKeyService apiKeyService;
    @Inject RoutingRuleLoader routingRuleLoader;
    @Inject DynamicVendorConfigWatcher dynamicVendorConfigWatcher;

    String originalRulesPath;
    String originalVendorsPath;

    @BeforeEach
    public final void beforeEach() {
        MockSmppServerResource.receivedPdus.clear();
        originalVendorsPath = dynamicVendorConfigWatcher.getConfigFilePath();
        dynamicVendorConfigWatcher.resetConfigStateForTest("./target/vendors.json");
        var testVendor =
                new VendorConf(
                        "testVendor_" + UUID.randomUUID(),
                        true,
                        "localhost",
                        MockSmppServerResource.PORT,
                        "test1",
                        "tost2",
                        1,
                        60,
                        0,
                        "SMPP");
        dynamicVendorConfigWatcher.persist(Set.of(testVendor));

        originalRulesPath = routingRuleLoader.getConfigRulesPath();
        routingRuleLoader.setConfigRulesPath("./target/rules.json");
        routingRuleLoader.persistRules(
                new HashMap<>(
                        Map.of(
                                RoutingRuleLoader.DEFAULT_RULE_GROUP_NAME,
                                new ArrayList<>(
                                        List.of(
                                                new RoutingRule(
                                                        "test",
                                                        new RoutingRule.Conditions(),
                                                        testVendor.getId(),
                                                        null))))));
    }

    @AfterEach
    public final void afterEach() {
        routingRuleLoader.setConfigRulesPath(originalRulesPath);
        routingRuleLoader.reloadRules();
        dynamicVendorConfigWatcher.resetConfigStateForTest(originalVendorsPath);
        dynamicVendorConfigWatcher.forceReloadConfig();
        MockSmppServerResource.receivedPdus.clear();
    }

    @Test
    void testFullDlrLifecycle() throws InterruptedException {
        // 1. Send SMS via API
        var smsDto = new IncomingSms();
        smsDto.setFrom("TestSender");
        smsDto.setTo("5534567890");
        smsDto.setText("PROMO2 Hello DLR Test");

        String internalId =
                given().contentType(MediaType.APPLICATION_JSON)
                        .body(smsDto)
                        .when()
                        .header(ApiKeyFilter.API_KEY_HEADER, ApiKeyService.ADMIN_DEFAULT_KEY)
                        .post("/api/sms/send")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("internalId");

        assertNotNull(internalId, "Internal ID should not be null");

        // 2. Verify the message was received by the mock SMPP server
        var receivedSubmitSm =
                (SubmitSm) MockSmppServerResource.receivedPdus.poll(1, TimeUnit.SECONDS);
        assertNotNull(receivedSubmitSm, "Mock SMPP server should have received a SubmitSm PDU.");
        assertEquals(smsDto.getFrom(), receivedSubmitSm.getSourceAddress().getAddress());
        assertEquals(smsDto.getTo(), receivedSubmitSm.getDestAddress().getAddress());

        // 3. Await and verify DLR state changes
        // Wait for the response from the mock server to be processed and status updated to SENT
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(
                        () -> {
                            DlrForwardingPayload payload =
                                    dlrMappingService.getDlrPayload(internalId);
                            assertNotNull(payload, "DLR payload should exist after SENT state.");
                            assertEquals(STATUS_SENT, payload.getStatus());
                            assertNotNull(
                                    payload.getSmscid(),
                                    "SMSC ID should be set by the SENT state.");
                            assertNotNull(payload.getSentAt(), "SentAt timestamp should be set.");
                        });

        // Wait for the DLR from the mock server to be processed and status updated to DELIVERED
        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(
                        () -> {
                            // Verify final state in DlrMappingService
                            DlrForwardingPayload finalPayload =
                                    dlrMappingService.getDlrPayload(internalId);
                            assertNotNull(finalPayload, "Final DLR payload should not be null.");
                            assertEquals(
                                    STATUS_DELIVERED,
                                    finalPayload.getStatus(),
                                    "Final status should be DELIVERED.");
                            assertNotNull(finalPayload.getRawDlr(), "Raw DLR should be recorded.");
                            assertNotNull(
                                    finalPayload.getProcessedAt(),
                                    "ProcessedAt timestamp should be set.");
                            // Check that forwarding was processed by checking forwardDate
                            // This assumes DlrForwardingService sets this once it has attempted
                            // forwarding
                            assertNotNull(
                                    finalPayload.getForwardDate(),
                                    "ForwardDate timestamp should be set if forwarding was processed.");
                        });
    }
}
