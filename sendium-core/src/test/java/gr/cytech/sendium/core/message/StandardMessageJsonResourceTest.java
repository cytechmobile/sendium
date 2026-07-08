package gr.cytech.sendium.core.message;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class StandardMessageJsonResourceTest {
    @Test
    void shouldDeserializeStandardMessageWithPrimitiveByteFields() {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "message": {
                            "from": "sender",
                            "to": "recipient",
                            "body": "hello",
                            "type": 0,
                            "dcs": 8
                          }
                        }
                        """)
                .when()
                .post("/test/standard-message-json")
                .then()
                .statusCode(200)
                .body("dcs", equalTo(8));
    }
}
