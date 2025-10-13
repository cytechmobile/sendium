package smsgateway.resource;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.common.mapper.TypeRef;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import smsgateway.auth.ApiKeyFilter;
import smsgateway.auth.ApiKeyService;

@QuarkusTest
public class ApiKeyResourceTest {
    @InjectMock ApiKeyService apiKeyService;

    @Test
    void testGetApiKeys_Success() {
        final var adminKey = ApiKeyService.ApiKey.admin("test-adminKey123");
        final var messageKey = ApiKeyService.ApiKey.message("test-messageKey123");
        final var smppKey = ApiKeyService.ApiKey.smpp("test-smppSystemId123", "smppPassword123");

        when(apiKeyService.getAdminKeys()).thenReturn(Map.of(adminKey.key(), adminKey));
        when(apiKeyService.getMessageKeys()).thenReturn(Map.of(messageKey.key(), messageKey));
        when(apiKeyService.getSmppCredentials()).thenReturn(Map.of(smppKey.systemId(), smppKey));
        when(apiKeyService.isAdminKeyValid(adminKey.key())).thenReturn(true);

        var keys =
                given().when()
                        .header(ApiKeyFilter.API_KEY_HEADER, adminKey.key())
                        .get("/api/admin/api-keys")
                        .then()
                        .statusCode(200)
                        .extract()
                        .body()
                        .as(new TypeRef<List<ApiKeyService.ApiKey>>() {});

        assertThat(keys).hasSize(3);
        assertThat(keys.getFirst().key()).isEqualTo(adminKey.key());
        assertThat(keys.get(1).key()).isEqualTo(messageKey.key());
        assertThat(keys.getLast().systemId()).isEqualTo(smppKey.systemId());

        verify(apiKeyService).getAdminKeys();
        verify(apiKeyService).getMessageKeys();
        verify(apiKeyService).getSmppCredentials();
    }

    @Test
    void testUpdateApiKeys_Success() throws IOException {
        var newKeys =
                List.of(
                        ApiKeyService.ApiKey.admin("newAdminKey"),
                        ApiKeyService.ApiKey.message("newMessageKey"),
                        ApiKeyService.ApiKey.smpp("smppSystemId123", "smppPassword123"));

        doNothing().when(apiKeyService).updateApiKeys(newKeys);
        when(apiKeyService.getAdminKeys())
                .thenReturn(Map.of(newKeys.getFirst().key(), newKeys.getFirst()));
        when(apiKeyService.isAdminKeyValid(newKeys.getFirst().key())).thenReturn(true);

        given().contentType("application/json")
                .body(newKeys)
                .when()
                .header(ApiKeyFilter.API_KEY_HEADER, newKeys.getFirst().key())
                .put("/api/admin/api-keys")
                .then()
                .statusCode(200);

        verify(apiKeyService).updateApiKeys(newKeys);
    }

    @Test
    void testUpdateApiKeys_NullPayload() throws IOException {
        final var adminKey = ApiKeyService.ApiKey.admin("newAdminKey");
        when(apiKeyService.getAdminKeys()).thenReturn(Map.of(adminKey.key(), adminKey));
        when(apiKeyService.isAdminKeyValid(adminKey.key())).thenReturn(true);
        given().contentType("application/json")
                .body(Collections.emptyMap()) // Or send actual null, but RestAssured might not like
                // it. Let's send empty.
                // The resource checks for specific keys.
                .when()
                .header(ApiKeyFilter.API_KEY_HEADER, adminKey.key())
                .put("/api/admin/api-keys")
                .then()
                .statusCode(400);

        verify(apiKeyService, never()).updateApiKeys(any());
    }

    @Test
    void testUpdateApiKeys_ServiceThrowsIOException() throws IOException {
        var newKeys =
                List.of(
                        ApiKeyService.ApiKey.admin("newAdminKey"),
                        ApiKeyService.ApiKey.message("newMessageKey"),
                        ApiKeyService.ApiKey.smpp("smppSystemId123", "smppPassword123"));
        doThrow(new IOException("Disk full")).when(apiKeyService).updateApiKeys(newKeys);
        when(apiKeyService.getAdminKeys())
                .thenReturn(Map.of(newKeys.getFirst().key(), newKeys.getFirst()));
        when(apiKeyService.isAdminKeyValid(newKeys.getFirst().key())).thenReturn(true);

        given().contentType("application/json")
                .body(newKeys)
                .when()
                .header(ApiKeyFilter.API_KEY_HEADER, newKeys.getFirst().key())
                .put("/api/admin/api-keys")
                .then()
                .statusCode(500);

        verify(apiKeyService).updateApiKeys(newKeys);
    }
}
