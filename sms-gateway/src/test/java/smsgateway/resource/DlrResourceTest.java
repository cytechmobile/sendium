package smsgateway.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import smsgateway.auth.ApiKeyFilter;
import smsgateway.auth.ApiKeyService;
import smsgateway.dto.DlrForwardingPayload;
import smsgateway.services.DlrMappingService;

@QuarkusTest
public class DlrResourceTest {
    public static final String API_KEY = "your-message-api-key";

    @InjectMock DlrMappingService dlrMappingServiceMock;
    @InjectMock ApiKeyService apiKeyService;

    private List<DlrForwardingPayload> mockPayloads;

    @BeforeEach
    public void setUp() {
        DlrForwardingPayload payload1 = new DlrForwardingPayload();
        payload1.setForwardingId("internalId1");
        payload1.setSmscid("vendorId1");
        payload1.setStatus("DELIVERED");
        payload1.setReceivedAt(LocalDateTime.of(2023, 1, 1, 10, 0, 0).toInstant(ZoneOffset.UTC));
        payload1.setSentAt(LocalDateTime.of(2023, 1, 1, 10, 1, 0).toInstant(ZoneOffset.UTC));
        payload1.setForwardDate(LocalDateTime.of(2023, 1, 1, 10, 2, 0).toInstant(ZoneOffset.UTC));
        payload1.setProcessedAt(LocalDateTime.of(2023, 1, 1, 10, 3, 0).toInstant(ZoneOffset.UTC));
        payload1.setFromAddress("sender1");
        payload1.setToAddress("receiver1");

        DlrForwardingPayload payload2 = new DlrForwardingPayload();
        payload2.setForwardingId("internalId2");
        payload2.setSmscid("vendorId2");
        payload2.setStatus("EXPIRED");
        payload2.setReceivedAt(LocalDateTime.of(2023, 1, 2, 11, 0, 0).toInstant(ZoneOffset.UTC));
        payload2.setSentAt(LocalDateTime.of(2023, 1, 2, 11, 1, 0).toInstant(ZoneOffset.UTC));
        payload2.setForwardDate(LocalDateTime.of(2023, 1, 2, 11, 2, 0).toInstant(ZoneOffset.UTC));
        payload2.setProcessedAt(LocalDateTime.of(2023, 1, 2, 11, 3, 0).toInstant(ZoneOffset.UTC));
        payload2.setFromAddress("sender2");
        payload2.setToAddress("receiver2");

        mockPayloads = Arrays.asList(payload1, payload2);

        // Mock the DlrMappingService behavior
        // The service method returns Collection<DlrForwardingPayload>
        when(apiKeyService.isMessageKeyValid(API_KEY)).thenReturn(true);
        when(apiKeyService.isMessageKeyValid(ApiKeyService.ADMIN_DEFAULT_KEY)).thenReturn(true);
        when(dlrMappingServiceMock.getAllDlrPayloads()).thenReturn(mockPayloads);
    }

    @Test
    public void testGetDlrStatusEndpoint() {
        given().when()
                .header(ApiKeyFilter.API_KEY_HEADER, API_KEY)
                .get("/api/dlr/status")
                .then()
                .statusCode(200)
                // Assuming default Jackson serialization for LocalDateTime (e.g., as array [year,
                // month, day, hour, minute, second])
                // or ISO string if quarkus-resteasy-reactive-jackson is configured with
                // JavaTimeModule.
                // Assertions updated for Instant (ISO 8601 string) and field name changes.
                .body("size()", is(mockPayloads.size()))
                .body("[0].forwardingId", is("internalId1"))
                .body("[0].smscid", is("vendorId1"))
                .body("[0].fromAddress", is("sender1"))
                .body("[0].toAddress", is("receiver1"))
                .body("[0].status", is("DELIVERED"))
                .body("[0].receivedAt", is("2023-01-01T10:00:00Z"))
                .body("[0].sentAt", is("2023-01-01T10:01:00Z"))
                .body("[0].forwardDate", is("2023-01-01T10:02:00Z"))
                .body("[0].processedAt", is("2023-01-01T10:03:00Z"))
                .body("[1].forwardingId", is("internalId2"))
                .body("[1].fromAddress", is("sender2"))
                .body("[1].toAddress", is("receiver2"))
                .body("[1].status", is("EXPIRED"));
        // Add more assertions as needed for all fields and payloads
    }

    @Test
    public void testGetDlrStatusEndpoint_Empty() {
        // Mock the DlrMappingService behavior for an empty list
        when(dlrMappingServiceMock.getAllDlrPayloads()).thenReturn(new ArrayList<>());

        given().when()
                .header(ApiKeyFilter.API_KEY_HEADER, API_KEY)
                .get("/api/dlr/status")
                .then()
                .statusCode(200)
                .body("size()", is(0));
    }

    @Test
    public void testGetDlrStatusEndpoint_Empty_AsAdmin() {
        // Mock the DlrMappingService behavior for an empty list
        when(dlrMappingServiceMock.getAllDlrPayloads()).thenReturn(new ArrayList<>());

        given().when()
                .header(ApiKeyFilter.API_KEY_HEADER, ApiKeyService.ADMIN_DEFAULT_KEY)
                .get("/api/dlr/status")
                .then()
                .statusCode(200)
                .body("size()", is(0));
    }
}
