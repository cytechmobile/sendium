package gr.cytech.sendium.core.storage.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.file.PendingTerminalCodec.Intent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNSUPPORTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingTerminalCodecTest {
    private final PendingTerminalCodec codec = new PendingTerminalCodec();
    private final ObjectMapper cbor = new ObjectMapper(new CBORFactory());

    @Test
    void roundTripsSourceAndClosureOwnership() {
        var intent = new Intent(UUID.randomUUID(), Set.of(new SourceId(UUID.randomUUID()), new SourceId(UUID.randomUUID())),
                Set.of(UUID.randomUUID()));
        assertThat(codec.decode(codec.encode(intent))).isEqualTo(intent);
    }

    @ParameterizedTest
    @ValueSource(strings = {"schemaVersion", "intentId", "sources", "closedGroups", "duplicateSource", "emptySources", "invalidUuid", "invalidSourceType"})
    void refusesMalformedIntentWithoutLeakingFieldValues(String mutation) throws Exception {
        var intent = new Intent(UUID.randomUUID(), Set.of(new SourceId(UUID.randomUUID())), Set.of());
        var node = (ObjectNode) cbor.readTree(codec.encode(intent));
        switch (mutation) {
            case "duplicateSource" -> node.withArray("sources").add(node.withArray("sources").get(0));
            case "emptySources" -> node.withArray("sources").removeAll();
            case "invalidUuid" -> node.put("intentId", "private invalid value");
            case "invalidSourceType" -> node.put("sources", "private invalid value");
            default -> node.remove(mutation);
        }
        byte[] bytes = cbor.writeValueAsBytes(node);
        assertThatThrownBy(() -> codec.decode(bytes)).hasMessage("pendingTerminalRecovery|error|reason:invalidIntent").hasNoCause();
    }

    @Test
    void distinguishesUnknownVersionAndRefusesTruncatedOrTrailingData() throws Exception {
        var intent = new Intent(UUID.randomUUID(), Set.of(new SourceId(UUID.randomUUID())), Set.of());
        byte[] valid = codec.encode(intent);
        var node = (ObjectNode) cbor.readTree(valid);
        node.put("schemaVersion", 999);
        byte[] unknown = cbor.writeValueAsBytes(node);
        assertThatThrownBy(() -> codec.decode(unknown)).isInstanceOfSatisfying(OutboundStorageException.class,
                failure -> assertThat(failure.reason()).isEqualTo(UNSUPPORTED));
        assertThatThrownBy(() -> codec.decode(null)).hasMessageContaining("reason:invalidIntent");
        assertThatThrownBy(() -> codec.decode(new byte[0])).hasMessageContaining("reason:invalidIntent");
        assertThatThrownBy(() -> codec.decode(Arrays.copyOf(valid, valid.length - 1))).hasMessageContaining("reason:invalidIntent");
        assertThatThrownBy(() -> codec.decode(Arrays.copyOf(valid, valid.length + 1))).hasMessageContaining("reason:invalidIntent");
    }
}
