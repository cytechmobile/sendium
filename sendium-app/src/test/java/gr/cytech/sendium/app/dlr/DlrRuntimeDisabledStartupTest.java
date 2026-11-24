package gr.cytech.sendium.app.dlr;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.pdu.Pdu;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import gr.cytech.sendium.app.storage.NativeMessageDlrModeHttpTest;
import gr.cytech.sendium.conf.SendiumConfigurationProvider;
import gr.cytech.sendium.core.message.DlrReturnMetadata;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundCoordinator;
import gr.cytech.sendium.core.queue.Queue;
import gr.cytech.sendium.core.smpp.server.InEvent;
import gr.cytech.sendium.core.smpp.server.SmppServerWorker;
import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import io.smallrye.health.SmallRyeHealthReporter;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Answers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DlrRuntimeDisabledStartupTest {
    @RegisterExtension
    static final QuarkusExtensionTest APP = NativeMessageDlrModeHttpTest.application("disabled");

    @TestHTTPResource("/api/v1/messages") URI nativeEndpoint;
    @TestHTTPResource("/sendsms") URI kannelEndpoint;
    @Inject OutboundCoordinator<StandardMessage> coordinator;
    @Inject SmallRyeHealthReporter health;

    @Test
    void nonDlrTrafficRemainsUsable() throws Exception {
        var rejected = NativeMessageDlrModeHttpTest.send(nativeEndpoint, true);
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(rejected.body()).contains("\"code\":\"INVALID_REQUEST\"");
        assertThat(NativeMessageDlrModeHttpTest.send(nativeEndpoint, false).statusCode()).isEqualTo(202);
        try (var client = HttpClient.newHttpClient()) {
            String parameters = "?username=http-user&password=secret&from=sender&to=12345&text=synthetic";
            var accepted = client.send(HttpRequest.newBuilder(URI.create(kannelEndpoint + parameters)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(accepted.statusCode()).isEqualTo(202);
            var callback = client.send(HttpRequest.newBuilder(URI.create(kannelEndpoint + parameters +
                            "&dlr-mask=3&dlr-url=https%3A%2F%2Fcallback.test%2Fdlr")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(callback.statusCode()).isEqualTo(400);
            assertThat(callback.body()).isEqualTo("Unsupported SMS submission");
        }
        // Exercise the real SMPP admission/acknowledgement path without opening a listener or provider connection.
        try (var worker = new Ingress()) {
            worker.setIngressCoordinator(coordinator);
            worker.enqueueIn(event(false));
            worker.enqueueIn(event(true));
            assertThat(worker.responses).extracting(SubmitSmResp::getCommandStatus)
                    .containsExactly(SmppConstants.STATUS_OK, SmppConstants.STATUS_SUBMITFAIL);
        }
        assertThat(coordinator.selectToRouter(8)).isEqualTo(3);
        assertThat(health.getReadiness().isDown()).isFalse();
        assertThat(health.getReadiness().getPayload().toString()).contains("\"backend\":\"disabled\"");
    }

    private static InEvent<StandardMessage> event(boolean dlr) {
        var message = new StandardMessage();
        message.from = "sender";
        message.to = "12345";
        message.body = "synthetic";
        message.acked = dlr;
        if (dlr) {
            message.dlrReturnMetadata = DlrReturnMetadata.smpp("account", "system", "sender", "12345");
        }
        return new InEvent<>(message, new SubmitSm(), 1, new Timestamp(1_000));
    }

    private static final class Ingress extends SmppServerWorker<StandardMessage> implements AutoCloseable {
        private final List<SubmitSmResp> responses = new ArrayList<>();

        private Ingress() {
            super(configuration(), "disabled-test", new Queue<>());
        }

        @Override
        public void enqueueOut(Pdu response) {
            responses.add((SubmitSmResp) response);
        }

        @Override
        public void close() {
            messagePartsHandler.stop();
        }
    }

    private static SendiumConfigurationProvider configuration() {
        return mock(SendiumConfigurationProvider.class, call -> {
            if (call.getMethod().getName().startsWith("get")) {
                Object[] args = call.getArguments();
                if (args.length == 1 && args[0] instanceof String[] property) {
                    Class<?> type = call.getMethod().getReturnType();
                    if (type == int.class) {
                        return Integer.parseInt(property[1]);
                    }
                    if (type == long.class) {
                        return Long.parseLong(property[1]);
                    }
                    return type == boolean.class ? Boolean.parseBoolean(property[1]) : property[1];
                }
                if (args.length == 2) {
                    return args[1];
                }
            }
            return Answers.RETURNS_DEFAULTS.answer(call);
        });
    }
}
