package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.DlrReturnMetadata.HttpCallbackFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static gr.cytech.sendium.core.worker.FileDlrTestSupport.httpState;
import static org.assertj.core.api.Assertions.assertThat;

class FileDlrAcceptanceTest {
    @TempDir
    Path root;

    @Test
    void acceptanceAndCorrelationSurviveReopen() {
        MessageState accepted = httpState();
        List<String> parts = accepted.getReassembledParts();
        try (var storage = FileDlrStorage.open(root)) {
            storage.recordProviderAccepted(accepted, "P", "X");
        }
        try (var reopened = FileDlrStorage.open(root)) {
            assertThat(reopened.correlationOwner("P", "X")).contains(accepted.getGatewayMsgId());
            assertThat(reopened.getState(accepted.getGatewayMsgId())).get().satisfies(state -> {
                assertThat(state.getAccountId()).isEqualTo("account");
                assertThat(state.getSystemId()).isEqualTo("system");
                assertThat(state.getSourceAddr()).isEqualTo("from");
                assertThat(state.getDestAddr()).isEqualTo("to");
                assertThat(state.getForwardDlrUrl()).isEqualTo("https://callback.test/dlr");
                assertThat(state.getHttpCallbackFormat()).isEqualTo(HttpCallbackFormat.JSON_POST);
                assertThat(state.getDlrMask()).isEqualTo(1);
                assertThat(state.getReassembledParts()).containsExactlyElementsOf(parts);
                assertThat(state.getDeliveryChannel()).isEqualTo(MessageState.DeliveryChannel.HTTP);
                assertThat(state.getStatus()).isEqualTo(MessageState.MessageStatus.SENT);
                assertThat(state.getDeliveryStatus()).isEqualTo(MessageState.DeliveryStatus.WAITING_PROVIDER);
                assertThat(state.getProviderName()).isEqualTo("P");
                assertThat(state.getProviderMessageId()).isEqualTo("X");
                assertThat(state.getDlrState()).isNull();
                assertThat(state.getResolvedAt()).isNull();
                assertThat(state.getTimestamp()).isEqualTo(accepted.getTimestamp());
            });
        }
    }

    @Test
    void multipartNamespacesAndOwnershipSurviveReopen() {
        MessageState g = httpState();
        MessageState h = httpState();
        MessageState j = httpState();
        MessageState first = httpState();
        MessageState second = httpState();
        try (var storage = FileDlrStorage.open(root)) {
            storage.recordProviderAccepted(g, "P", "X1");
            storage.recordProviderAccepted(g, "P", "X2");
            storage.recordProviderAccepted(h, "Q", "X1");
            storage.recordProviderAccepted(j, "P", "X1");
            storage.recordProviderAccepted(first, "P", "Y");
            storage.recordProviderAccepted(second, "P", "Y");
        }
        try (var reopened = FileDlrStorage.open(root)) {
            assertThat(reopened.correlationOwner("P", "X1")).contains(j.getGatewayMsgId());
            assertThat(reopened.correlationOwner("P", "X2")).contains(g.getGatewayMsgId());
            assertThat(reopened.correlationOwner("Q", "X1")).contains(h.getGatewayMsgId());
            assertThat(reopened.getState(g.getGatewayMsgId()).orElseThrow().getProviderMessageId()).isEqualTo("X2");
            assertThat(reopened.getState(j.getGatewayMsgId()).orElseThrow().getProviderMessageId()).isEqualTo("X1");
            assertThat(reopened.correlationOwner("P", "Y")).contains(second.getGatewayMsgId());
            MessageState previous = reopened.getState(first.getGatewayMsgId()).orElseThrow();
            assertThat(previous.getProviderName()).isNull();
            assertThat(previous.getProviderMessageId()).isNull();
        }
    }

    @Test
    void callerMutationCannotChangeStoredState() {
        MessageState accepted = httpState();
        List<String> parts = accepted.getReassembledParts();
        try (var storage = FileDlrStorage.open(root)) {
            storage.recordProviderAccepted(accepted, "P", "X");
            accepted.setDlrMask(3);
            accepted.setReassembledParts(List.of("changed"));
            accepted.setProviderMessageId("changed");

            MessageState returned = storage.getState(accepted.getGatewayMsgId()).orElseThrow();
            returned.setDlrMask(0);
            returned.setDeliveryStatus(MessageState.DeliveryStatus.FAILED);

            MessageState stored = storage.getState(accepted.getGatewayMsgId()).orElseThrow();
            assertThat(stored.getDlrMask()).isEqualTo(1);
            assertThat(stored.getReassembledParts()).containsExactlyElementsOf(parts);
            assertThat(stored.getProviderMessageId()).isEqualTo("X");
            assertThat(stored.getDeliveryStatus()).isEqualTo(MessageState.DeliveryStatus.WAITING_PROVIDER);
        }
    }
}
