package gr.cytech.sendium.app.storage;

import gr.cytech.sendium.core.message.DlrReturnMetadata;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.PendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemoryPendingMessageStore;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import gr.cytech.sendium.core.storage.pending.PendingRecordCodec;
import gr.cytech.sendium.core.storage.pending.PendingSourceRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StandaloneMessageSnapshotTest {
    @Test
    void normalProtocolRecordPreservesProcessingInputs() {
        for (var metadata : List.of(DlrReturnMetadata.http("account", "sender", "recipient", "https://callback.test/dlr"),
                DlrReturnMetadata.smpp("account", "system", "sender", "recipient"))) {
            var message = new StandardMessage();
            SourceId source = new SourceId(UUID.randomUUID());
            message.serial = source.value().toString();
            message.owner_id = "account";
            message.systemId = "system";
            message.from = "sender";
            message.to = "recipient";
            message.body = "Hello";
            message.priority = 3;
            message.ttl = 120;
            message.ddt = 5;
            message.dcs = StandardMessage.DCS_16BIT;
            message.nextTarget = "provider";
            message.field1 = 7;
            message.field2 = 8;
            message.field3 = 1;
            message.field4 = "billing-info";
            message.acked = true;
            message.dlrReturnMetadata = metadata;
            message.tlvs = new HashMap<>(Map.of("0427", "1"));
            message.attrs = new HashMap<>(Map.of("request", "original"));

            try (PendingMessageStore<StandardMessage> pending = new MemoryPendingMessageStore<>(1, StandaloneMessageSnapshot::copy)) {
                pending.open();
                SourceId accepted = pending.admit(source, message);
                var retained = pending.find(accepted).orElseThrow();
                message.field4 = "changed after admission";
                message.putAttr("routing-only", "not accepted input");
                message.tlvs.clear();
                var codec = new PendingRecordCodec();
                var record = new PendingSourceRecord(accepted, PendingSourceRecord.Disposition.READY, PendingMessageData.capture(retained));
                StandardMessage restored = codec.decode(codec.encode(record)).message().restore();
                assertThat(restored).usingRecursiveComparison().isEqualTo(retained);
                assertThat(restored.field1).isEqualTo(7);
                assertThat(restored.field2).isEqualTo(8);
                assertThat(restored.field3).isEqualTo(1);
                assertThat(restored.field4).isEqualTo("billing-info");
                assertThat(restored.dlrReturnMetadata).isEqualTo(metadata);
                assertThat(restored.tlvs).containsEntry("0427", "1");
                assertThat(restored.attrs).containsExactlyEntriesOf(Map.of("request", "original"));
            }
        }
    }

    @Test
    void snapshotDetachesProtocolCollectionsAndMutableAttributeValues() {
        var message = new StandardMessage();
        var bytes = new byte[]{1, 2};
        var nested = new ArrayList<>(List.of("original"));
        message.attrs = new HashMap<>();
        message.attrs.put("bytes", bytes);
        message.attrs.put("nested", nested);
        message.field1 = new ArrayList<>(List.of("field"));
        message.tlvs = new HashMap<>();
        message.tlvs.put("1400", "original");
        message.reassembledParts = new ArrayList<>(List.of("part"));
        var snapshot = StandaloneMessageSnapshot.copy(message);

        bytes[0] = 9;
        nested.clear();
        ((List<?>) message.field1).clear();
        message.tlvs.clear();
        message.reassembledParts.clear();

        assertThat((byte[]) snapshot.attrs.get("bytes")).containsExactly((byte) 1, (byte) 2);
        assertThat((List<?>) snapshot.attrs.get("nested")).singleElement().isEqualTo("original");
        assertThat((List<?>) snapshot.field1).singleElement().isEqualTo("field");
        assertThat(snapshot.tlvs).containsEntry("1400", "original");
        assertThat(snapshot.reassembledParts).containsExactly("part");
    }

    @Test
    void unsupportedMutableFieldsAndCustomMessageTypesRequireExplicitApplicationAssembly() {
        var message = new StandardMessage();
        message.field1 = new StringBuilder("mutable");
        assertThatThrownBy(() -> StandaloneMessageSnapshot.copy(message))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported mutable");
        assertThatThrownBy(() -> StandaloneMessageSnapshot.copy(new CustomMessage()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("application-owned");
    }

    static class CustomMessage extends StandardMessage {
    }
}
