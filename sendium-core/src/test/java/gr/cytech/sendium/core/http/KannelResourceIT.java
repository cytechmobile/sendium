package gr.cytech.sendium.core.http;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.queue.Queue;
import gr.cytech.sendium.core.worker.InMemoryDlrService;
import gr.cytech.sendium.core.worker.MessageState;
import gr.cytech.sendium.routing.OutgoingWorkerManager;
import gr.cytech.sendium.routing.StandardOutgoingWorkerHandler;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import utils.CaptorWorker;

import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class KannelResourceIT {
    static StandardOutgoingWorkerHandler outgoingWorkerHandler;
    static InMemoryDlrService dlrService;

    CaptorWorker captorWorker;
    private final String usernamekannel = "test2";
    private final String passwordKannel = "123qwe";
    private final String captorInstanceName = "captorTest";

    @BeforeAll
    static void beforeAll() {
        outgoingWorkerHandler = (StandardOutgoingWorkerHandler) CDI.current().select(OutgoingWorkerManager.class).get();
        dlrService = CDI.current().select(InMemoryDlrService.class).get();
    }

    @BeforeEach
    void setUp() {
        captorWorker = (CaptorWorker) outgoingWorkerHandler.getWorkers().get(captorInstanceName);
        captorWorker.getRouterQueue().drainTo(new Queue<>());
        captorWorker.captures.clear();
    }

    @Test
    @DisplayName("Should return 400 BAD REQUEST when 'to' parameter is missing")
    void testMissingToParameter() {
        given()
                .queryParam("username", usernamekannel)
                .queryParam("password", passwordKannel)
                .queryParam("from", "Sender")
                .queryParam("text", "Hello World")
                .when()
                .get("/sendsms")
                .then()
                .statusCode(400)
                .body(org.hamcrest.Matchers.equalTo("Missing 'to' parameter"));
    }

    @Test
    @DisplayName("Should return 400 BAD REQUEST when 'from' parameter is missing")
    void testMissingFromParameter() {
        given()
                .queryParam("to", "123456789")
                .queryParam("username", usernamekannel)
                .queryParam("password", passwordKannel)
                .queryParam("text", "Hello World")
                .when()
                .get("/sendsms")
                .then()
                .statusCode(400)
                .body(org.hamcrest.Matchers.equalTo("Missing 'from' parameter"));
    }

    @Test
    @DisplayName("Should return 400 BAD REQUEST when 'text' parameter is missing")
    void testMissingTextParameter() {
        given()
                .queryParam("to", "123456789")
                .queryParam("from", "Sender")
                .queryParam("username", usernamekannel)
                .queryParam("password", passwordKannel)
                .when()
                .get("/sendsms")
                .then()
                .statusCode(400)
                .body(org.hamcrest.Matchers.equalTo("Missing 'text' parameter"));
    }

    @Test
    @DisplayName("Should return 202 ACCEPTED and properly route a standard 7-bit message")
    void testStandardMessageRouting() throws InterruptedException {
        given()
                .queryParam("to", "123456789")
                .queryParam("text", "Hello%20World") // URL encoded "Hello World"
                .queryParam("from", "Sender")
                .queryParam("username", usernamekannel)
                .queryParam("password", passwordKannel)
                .when()
                .get("/sendsms")
                .then()
                .statusCode(202);

        var capturedMsg = captorWorker.captures.poll(10, TimeUnit.SECONDS);

        assertNotNull(capturedMsg.serial);
        assertEquals("123456789", capturedMsg.to);
        assertEquals("Hello World", capturedMsg.body);
        assertEquals("Sender", capturedMsg.from);
    }

    @Test
    @DisplayName("Should correctly decode text when explicit charset (ISO-8859-7) is provided")
    void testExplicitCharsetDecoding() throws InterruptedException {
        // "Γεια" (Greek for Hello) encoded in ISO-8859-7 is %C3%E5%E9%E1
        given()
                .queryParam("to", "123456")
                .queryParam("from", "Sender")
                .queryParam("text", "%C3%E5%E9%E1")
                .queryParam("charset", "ISO-8859-7")
                .queryParam("username", usernamekannel)
                .queryParam("password", passwordKannel)
                .when()
                .get("/sendsms")
                .then()
                .statusCode(202);

        var capturedMsg = captorWorker.captures.poll(10, TimeUnit.SECONDS);
        assertThat(capturedMsg).isNotNull();
        assertThat(capturedMsg.body).isEqualTo("Γεια");
    }

    @Test
    @DisplayName("Should fallback to UTF-16BE when coding=2 (UCS-2) and no charset is provided")
    void testImplicitUcs2CharsetDecoding() throws InterruptedException {
        // "Hi" in UTF-16BE is 0x00 0x48 0x00 0x69 -> URL encoded: %00%48%00%69
        given()
                .queryParam("to", "123456")
                .queryParam("from", "Sender")
                .queryParam("text", "%00%48%00%69")
                .queryParam("coding", "2")
                .queryParam("username", usernamekannel)
                .queryParam("password", passwordKannel)
                .when()
                .get("/sendsms")
                .then()
                .statusCode(202);

        var capturedMsg = captorWorker.captures.poll(10, TimeUnit.SECONDS);
        assertThat(capturedMsg).isNotNull();
        assertThat(capturedMsg.body).isEqualTo("Hi");
        assertThat(capturedMsg.dcs).isEqualTo(StandardMessage.DCS_16BIT);
    }

    @Test
    @DisplayName("Should fallback to UTF-8 gracefully if an invalid charset is provided")
    void testInvalidCharsetFallback() throws InterruptedException {
        // "Hello" in standard UTF-8 URL encoding
        given()
                .queryParam("to", "123456")
                .queryParam("text", "Hello")
                .queryParam("from", "Sender")
                .queryParam("charset", "INVALID-CHARSET-999")
                .queryParam("user", usernamekannel)
                .queryParam("pass", passwordKannel)
                .when()
                .get("/sendsms")
                .then()
                .statusCode(202);

        var capturedMsg = captorWorker.captures.poll(10, TimeUnit.SECONDS);
        assertThat(capturedMsg).isNotNull();
        assertThat(capturedMsg.body).isEqualTo("Hello");
    }

    @Test
    @DisplayName("Should return 401 Anauthorized when invalid creds provided")
    void testInvalidCredentials() {
        given()
                .queryParam("to", "123456789")
                .queryParam("from", "Sender")
                .queryParam("username", "test1")
                .queryParam("password", "test2")
                .when()
                .get("/sendsms")
                .then()
                .statusCode(401)
                .body(org.hamcrest.Matchers.equalTo("Invalid credentials"));
    }

    @Test
    @DisplayName("Should return 401 Unauthorized when password is missing")
    void testMissingPassword() {
        given()
                .queryParam("to", "123456789")
                .queryParam("from", "Sender")
                .queryParam("text", "Hello World")
                .queryParam("username", usernamekannel)
                .when()
                .get("/sendsms")
                .then()
                .statusCode(401)
                .body(org.hamcrest.Matchers.equalTo("Invalid credentials"));
    }

    @Test
    @DisplayName("Should prefer user/pass aliases over username/password")
    void testUserPassAliasesOverrideUsernamePassword() throws InterruptedException {
        given()
                .queryParam("to", "123456789")
                .queryParam("from", "Sender")
                .queryParam("text", "Hello Alias")
                .queryParam("username", "bad-user")
                .queryParam("password", "bad-pass")
                .queryParam("user", usernamekannel)
                .queryParam("pass", passwordKannel)
                .when()
                .get("/sendsms")
                .then()
                .statusCode(202);

        var capturedMsg = captorWorker.captures.poll(10, TimeUnit.SECONDS);
        assertThat(capturedMsg).isNotNull();
        assertThat(capturedMsg.owner_id).isEqualTo(usernamekannel);
        assertThat(capturedMsg.body).isEqualTo("Hello Alias");
    }

    @Test
    @DisplayName("Should map optional Kannel fields and create initial DLR state")
    void testOptionalFieldsAndDlrStateCreation() throws InterruptedException {
        String serial = given()
                .queryParam("to", "987654321")
                .queryParam("from", "Sender")
                .queryParam("text", "Binary-ish")
                .queryParam("username", usernamekannel)
                .queryParam("password", passwordKannel)
                .queryParam("account", "customer-account")
                .queryParam("smsc", "smsc-route")
                .queryParam("mclass", 1)
                .queryParam("coding", 1)
                .queryParam("validity", 60)
                .queryParam("deferred", 5)
                .queryParam("udh", "050003010201")
                .queryParam("pid", 12)
                .queryParam("alt-dcs", 8)
                .queryParam("rpi", 1)
                .queryParam("binfo", "billing-info")
                .queryParam("priority", 3)
                .queryParam("dlr-url", "http://callback.test/dlr?id=%I&status=%d")
                .when()
                .get("/sendsms")
                .then()
                .statusCode(202)
                .extract()
                .asString();

        var capturedMsg = captorWorker.captures.poll(10, TimeUnit.SECONDS);
        assertThat(capturedMsg).isNotNull();
        assertThat(capturedMsg.serial).isEqualTo(serial);
        assertThat(capturedMsg.owner_id).isEqualTo("customer-account");
        assertThat(capturedMsg.message_center).isEqualTo("smsc-route");
        assertThat(capturedMsg.mclass).isEqualTo(1);
        assertThat(capturedMsg.dcs).isEqualTo(StandardMessage.DCS_8BIT);
        assertThat(capturedMsg.ttl).isEqualTo(60);
        assertThat(capturedMsg.ddt).isEqualTo(5);
        assertThat(capturedMsg.binheader).isEqualTo("050003010201");
        assertThat(capturedMsg.field1).isEqualTo(12);
        assertThat(capturedMsg.field2).isEqualTo(8);
        assertThat(capturedMsg.field3).isEqualTo(1);
        assertThat(capturedMsg.field4).isEqualTo("billing-info");
        assertThat(capturedMsg.priority).isEqualTo(3);
        assertThat(capturedMsg.acked).isTrue();

        var state = dlrService.getState(serial);
        assertThat(state).isPresent();
        assertThat(state.get().getGatewayMsgId()).isEqualTo(serial);
        assertThat(state.get().getAccountId()).isEqualTo(usernamekannel);
        assertThat(state.get().getSystemId()).isEqualTo(usernamekannel);
        assertThat(state.get().getSourceAddr()).isEqualTo("Sender");
        assertThat(state.get().getDestAddr()).isEqualTo("987654321");
        assertThat(state.get().getForwardDlrUrl()).isEqualTo("http://callback.test/dlr?id=%I&status=%d");
        assertThat(state.get().getStatus()).isEqualTo(MessageState.MessageStatus.ACCEPTED);
    }
}
