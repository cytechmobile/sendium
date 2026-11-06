package gr.cytech.sendium.core.storage.pending;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.pending.PendingPublicationCodec.Publication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingPublicationCodecTest {
    private final PendingPublicationCodec codec = new PendingPublicationCodec();
    private final ObjectMapper cbor = new ObjectMapper(new CBORFactory());

    @Test
    void executionPayloadDoesNotRequireAnAcceptedSourceIdentity() {
        var message = new StandardMessage();
        message.serial = null;
        message.body = "assembled execution";
        var source = new SourceId(UUID.randomUUID());
        var publication = new Publication(UUID.randomUUID(), Set.of(source), PendingMessageData.capture(message));
        var decoded = codec.decode(codec.encode(publication));
        assertThat(decoded.id()).isEqualTo(publication.id());
        assertThat(decoded.sources()).containsExactly(source);
        assertThat(decoded.message().restore().serial).isNull();
        assertThat(decoded.message().restore().body).isEqualTo("assembled execution");
    }

    @ParameterizedTest
    @ValueSource(strings = {"schemaVersion", "publicationId", "sources", "message", "duplicateSource", "emptySources", "invalidVersion", "invalidUuid"})
    void malformedSnapshotsUseFixedPayloadFreeDiagnostics(String mutation) throws Exception {
        var message = new StandardMessage();
        message.body = "private message value";
        byte[] valid = codec.encode(new Publication(UUID.randomUUID(), Set.of(new SourceId(UUID.randomUUID())), PendingMessageData.capture(message)));
        var node = (ObjectNode) cbor.readTree(valid);
        switch (mutation) {
            case "duplicateSource" -> node.withArray("sources").add(node.withArray("sources").get(0));
            case "emptySources" -> node.withArray("sources").removeAll();
            case "invalidVersion" -> node.put("schemaVersion", 999);
            case "invalidUuid" -> node.put("publicationId", "private message value");
            default -> node.remove(mutation);
        }
        byte[] corrupted = cbor.writeValueAsBytes(node);
        assertThatThrownBy(() -> codec.decode(corrupted)).hasMessage("pendingPublicationRead|error|reason:invalidSnapshot")
                .hasNoCause();
    }

    @Test
    void refusesNullEmptyTruncatedAndTrailingData() {
        assertThatThrownBy(() -> codec.decode(null)).hasMessageContaining("reason:invalidSnapshot");
        assertThatThrownBy(() -> codec.decode(new byte[0])).hasMessageContaining("reason:invalidSnapshot");
        assertThatThrownBy(() -> codec.decode(new byte[]{(byte) 0xbf})).hasMessageContaining("reason:invalidSnapshot");
        var message = new StandardMessage();
        byte[] encoded = codec.encode(new Publication(UUID.randomUUID(), Set.of(new SourceId(UUID.randomUUID())), PendingMessageData.capture(message)));
        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThatThrownBy(() -> codec.decode(trailing)).hasMessageContaining("reason:invalidSnapshot");
    }
}
