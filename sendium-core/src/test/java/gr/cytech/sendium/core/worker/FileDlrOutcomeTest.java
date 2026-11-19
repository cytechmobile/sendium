package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.DlrReturnMetadata.HttpCallbackFormat;
import gr.cytech.sendium.core.message.StandardMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static gr.cytech.sendium.core.worker.FileDlrTestSupport.httpState;
import static org.assertj.core.api.Assertions.assertThat;

class FileDlrOutcomeTest {
    @TempDir
    Path root;

    @Test
    void terminalResolutionConsumesAllPartsOnce() {
        MessageState g = httpState();
        try (var storage = FileDlrStorage.open(root)) {
            storage.recordProviderAccepted(g, "P", "X1");
            storage.recordProviderAccepted(g, "P", "X2");
            assertThat(storage.resolveDlr("P", "X1", MessageState.MessageStatus.DELIVERED,
                    StandardMessage.DLR_STAT_DELIVRD, "0")).get().satisfies(resolved -> {
                        assertThat(resolved.getGatewayMsgId()).isEqualTo(g.getGatewayMsgId());
                        assertThat(resolved.getDeliveryStatus()).isEqualTo(MessageState.DeliveryStatus.PENDING);
                        assertThat(resolved.getProviderMessageId()).isEqualTo("X1");
                        assertThat(resolved.getNextAttemptAt()).isNotNull();
                    });
            assertThat(storage.correlationOwner("P", "X1")).isEmpty();
            assertThat(storage.correlationOwner("P", "X2")).isEmpty();
            assertThat(storage.resolveDlr("P", "X2", MessageState.MessageStatus.FAILED, 2, "1")).isEmpty();
            assertThat(storage.resolveDlr("P", "X1", MessageState.MessageStatus.FAILED, 2, "1")).isEmpty();
        }
        try (var reopened = FileDlrStorage.open(root)) {
            MessageState stored = reopened.getState(g.getGatewayMsgId()).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(MessageState.MessageStatus.DELIVERED);
            assertThat(stored.getDeliveryStatus()).isEqualTo(MessageState.DeliveryStatus.PENDING);
            assertThat(stored.getResolvedAt()).isNotNull();
        }
    }

    @Test
    void intermediateReceiptPreservesCorrelation() {
        MessageState g = httpState();
        try (var storage = FileDlrStorage.open(root)) {
            var service = new DlrService();
            service.storage = storage;
            storage.recordProviderAccepted(g, "P", "X");

            assertThat(service.resolveDlr("P", "X", StandardMessage.DLR_STAT_ACCEPTD, "0")).isEmpty();
            assertThat(service.resolveDlr("P", "X", StandardMessage.DLR_STAT_BUFFRED, "0")).isEmpty();
            assertThat(storage.correlationOwner("P", "X")).contains(g.getGatewayMsgId());
            assertThat(storage.getState(g.getGatewayMsgId()).orElseThrow().getDeliveryStatus())
                    .isEqualTo(MessageState.DeliveryStatus.WAITING_PROVIDER);

            assertThat(service.resolveDlr("P", "X", StandardMessage.DLR_STAT_DELIVRD, "0")).isPresent();
        }
    }

    @Test
    void rejectionPersistsWithoutInventedCorrelation() {
        MessageState unidentified = httpState();
        MessageState correlated = httpState();
        try (var storage = FileDlrStorage.open(root)) {
            assertThat(storage.recordProviderRejected(unidentified, "P", null, StandardMessage.DLR_STAT_FAILED, "22"))
                    .get().satisfies(rejected -> {
                        assertThat(rejected.getStatus()).isEqualTo(MessageState.MessageStatus.FAILED);
                        assertThat(rejected.getDeliveryStatus()).isEqualTo(MessageState.DeliveryStatus.PENDING);
                        assertThat(rejected.getProviderName()).isNull();
                        assertThat(rejected.getLastDeliveryResult()).isEqualTo("provider_rejected");
                    });

            storage.recordProviderAccepted(correlated, "P", "X");
            assertThat(storage.recordProviderRejected(correlated, "P", null, StandardMessage.DLR_STAT_FAILED, "23"))
                    .isPresent();
            assertThat(storage.correlationOwner("P", "X")).isEmpty();
            assertThat(storage.resolveDlr("P", "X", MessageState.MessageStatus.DELIVERED, 1, "0")).isEmpty();
            // A second terminal outcome does not replace the first.
            assertThat(storage.recordProviderRejected(correlated, "P", null, StandardMessage.DLR_STAT_FAILED, "99"))
                    .isEmpty();
        }
        try (var reopened = FileDlrStorage.open(root)) {
            assertThat(reopened.getState(unidentified.getGatewayMsgId()).orElseThrow().getErrorCode()).isEqualTo("22");
            assertThat(reopened.getState(correlated.getGatewayMsgId()).orElseThrow().getErrorCode()).isEqualTo("23");
        }
    }

    @Test
    void returnMetadataAndMaskSemanticsSurviveReopen() {
        MessageState http = httpState();
        MessageState smpp = new MessageState(UUID.randomUUID().toString(), "account", "smpp-system", "a", "b", null);
        smpp.setDeliveryChannel(MessageState.DeliveryChannel.SMPP);
        smpp.setDlrMask(2);
        smpp.setReassembledParts(List.of("part-1", "part-2"));
        try (var storage = FileDlrStorage.open(root)) {
            storage.recordProviderAccepted(http, "P", "H");
            storage.recordProviderAccepted(smpp, "P", "S");
            storage.resolveDlr("P", "H", MessageState.MessageStatus.FAILED, StandardMessage.DLR_STAT_FAILED, "7");
            storage.resolveDlr("P", "S", MessageState.MessageStatus.DELIVERED, StandardMessage.DLR_STAT_DELIVRD, "0");
        }
        try (var reopened = FileDlrStorage.open(root)) {
            assertThat(reopened.getState(http.getGatewayMsgId())).get().satisfies(state -> {
                assertThat(state.getDeliveryChannel()).isEqualTo(MessageState.DeliveryChannel.HTTP);
                assertThat(state.getHttpCallbackFormat()).isEqualTo(HttpCallbackFormat.JSON_POST);
                assertThat(state.getDlrMask()).isEqualTo(1);
                assertThat(state.getDlrState()).isEqualTo(StandardMessage.DLR_STAT_FAILED);
                assertThat(state.getErrorCode()).isEqualTo("7");
                assertThat(state.getForwardDlrUrl()).isEqualTo("https://callback.test/dlr");
            });
            assertThat(reopened.getState(smpp.getGatewayMsgId())).get().satisfies(state -> {
                assertThat(state.getDeliveryChannel()).isEqualTo(MessageState.DeliveryChannel.SMPP);
                assertThat(state.getSystemId()).isEqualTo("smpp-system");
                assertThat(state.getDlrMask()).isEqualTo(2);
                assertThat(state.getReassembledParts()).containsExactly("part-1", "part-2");
                assertThat(state.getNextAttemptAt()).isNull();
                assertThat(state.getStatus()).isEqualTo(MessageState.MessageStatus.DELIVERED);
            });
        }
    }
}
