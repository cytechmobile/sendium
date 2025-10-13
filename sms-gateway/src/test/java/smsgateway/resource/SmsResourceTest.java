package smsgateway.resource;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import smsgateway.auth.ApiKeyFilter;
import smsgateway.auth.ApiKeyService;
import smsgateway.dto.IncomingSms;

@QuarkusTest
public class SmsResourceTest {

    @Inject ApiKeyService apiKeyService;

    @BeforeEach
    public void beforeAll() throws IOException {
        var keys = new ArrayList<>(apiKeyService.getAdminKeys().values().stream().toList());
        var httpKey = ApiKeyService.ApiKey.message(DlrResourceTest.API_KEY);
        keys.add(httpKey);
        apiKeyService.updateApiKeys(keys);
    }

    @Test
    public void testPostValidSms() {
        IncomingSms sms = new IncomingSms();
        sms.setFrom("111");
        sms.setTo("222");
        sms.setText("Test message");
        sms.setTimestamp("2023-01-01T12:00:00Z");

        given().contentType(ContentType.JSON)
                .body(sms)
                .when()
                .header(ApiKeyFilter.API_KEY_HEADER, DlrResourceTest.API_KEY)
                .post("/api/sms/send")
                .then()
                .statusCode(200)
                .body("status", is("Message received"));
    }

    @Test
    public void testPostValidSmsAsAdmin() {
        IncomingSms sms = new IncomingSms();
        sms.setFrom("111");
        sms.setTo("222");
        sms.setText("Test message");
        sms.setTimestamp("2023-01-01T12:00:00Z");

        given().contentType(ContentType.JSON)
                .body(sms)
                .when()
                .header(ApiKeyFilter.API_KEY_HEADER, ApiKeyService.ADMIN_DEFAULT_KEY)
                .post("/api/sms/send")
                .then()
                .statusCode(200)
                .body("status", is("Message received"));
    }

    @Test
    public void testPostInvalidSmsMissingText() {
        IncomingSms sms = new IncomingSms();
        sms.setFrom("111");
        sms.setTo("222");
        sms.setText(null); // or sms.setText("");
        sms.setTimestamp("2023-01-01T12:00:00Z");

        var resp =
                given().contentType(ContentType.JSON)
                        .body(sms)
                        .when()
                        .header(ApiKeyFilter.API_KEY_HEADER, DlrResourceTest.API_KEY)
                        .post("/api/sms/send")
                        .then()
                        .statusCode(400)
                        .extract()
                        .response()
                        .body()
                        .as(SmsResource.MessageResponse.class);
        assertThat(resp.getError())
                .isEqualTo(new SmsResource.MessageResponse("Invalid request payload").getError());
    }

    @Test
    public void testPostInvalidSmsMissingFrom() {
        IncomingSms sms = new IncomingSms();
        sms.setFrom(null); // or sms.setFrom("");
        sms.setTo("222");
        sms.setText("Test message");
        sms.setTimestamp("2023-01-01T12:00:00Z");

        var resp =
                given().contentType(ContentType.JSON)
                        .body(sms)
                        .when()
                        .header(ApiKeyFilter.API_KEY_HEADER, DlrResourceTest.API_KEY)
                        .post("/api/sms/send")
                        .then()
                        .statusCode(400)
                        .extract()
                        .response()
                        .body()
                        .as(SmsResource.MessageResponse.class);
        assertThat(resp.getError())
                .isEqualTo(new SmsResource.MessageResponse("Invalid request payload").getError());
    }

    @Test
    public void testPostInvalidSmsMissingTo() {
        IncomingSms sms = new IncomingSms();
        sms.setFrom("111");
        sms.setTo(null); // or sms.setTo("");
        sms.setText("Test message");
        sms.setTimestamp("2023-01-01T12:00:00Z");

        var resp =
                given().contentType(ContentType.JSON)
                        .body(sms)
                        .when()
                        .header(ApiKeyFilter.API_KEY_HEADER, DlrResourceTest.API_KEY)
                        .post("/api/sms/send")
                        .then()
                        .statusCode(400)
                        .extract()
                        .response()
                        .body()
                        .as(SmsResource.MessageResponse.class);
        assertThat(resp.getError())
                .isEqualTo(new SmsResource.MessageResponse("Invalid request payload").getError());
    }
}
