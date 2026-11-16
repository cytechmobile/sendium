package gr.cytech.sendium.core.storage.pending;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.pending.PendingPublicationCodec.Publication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingPublicationCodecTest {
    private final PendingPublicationCodec codec = new PendingPublicationCodec();
    private final ObjectMapper cbor = new ObjectMapper(new CBORFactory());

    @Test
    void recipeContainsSourceReferencesButNoExecutionPayload() throws Exception {
        var source = new SourceId(UUID.randomUUID());
        var publication = new Publication(UUID.randomUUID(), Set.of(source), List.of(source), 2, false);
        var decoded = codec.decode(codec.encode(publication));
        assertThat(decoded.id()).isEqualTo(publication.id());
        assertThat(decoded.sources()).containsExactly(source);
        assertThat(decoded).isEqualTo(publication);
        assertThat(cbor.readTree(codec.encode(publication)).has("message")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"schemaVersion", "publicationId", "sources", "originals", "priority", "assembled", "duplicateSource", "emptySources", "invalidVersion", "invalidUuid", "payload", "duplicateOriginal", "invalidPriority"})
    void malformedRecipesUseFixedPayloadFreeDiagnostics(String mutation) throws Exception {
        var source = new SourceId(UUID.randomUUID());
        byte[] valid = codec.encode(new Publication(UUID.randomUUID(), Set.of(source), List.of(source), 2, false));
        var node = (ObjectNode) cbor.readTree(valid);
        switch (mutation) {
            case "duplicateSource" -> node.withArray("sources").add(node.withArray("sources").get(0));
            case "emptySources" -> node.withArray("sources").removeAll();
            case "invalidVersion" -> node.put("schemaVersion", 999);
            case "invalidUuid" -> node.put("publicationId", "private message value");
            case "payload" -> node.put("message", "private message value");
            case "duplicateOriginal" -> node.withArray("originals").add(node.withArray("originals").get(0));
            case "invalidPriority" -> node.put("priority", 4);
            default -> node.remove(mutation);
        }
        byte[] corrupted = cbor.writeValueAsBytes(node);
        assertThatThrownBy(() -> codec.decode(corrupted)).hasMessage("pendingPublicationRead|error|reason:invalidRecipe")
                .hasNoCause();
    }

    @Test
    void refusesNullEmptyTruncatedAndTrailingData() {
        assertThatThrownBy(() -> codec.decode(null)).hasMessageContaining("reason:invalidRecipe");
        assertThatThrownBy(() -> codec.decode(new byte[0])).hasMessageContaining("reason:invalidRecipe");
        assertThatThrownBy(() -> codec.decode(new byte[]{(byte) 0xbf})).hasMessageContaining("reason:invalidRecipe");
        var source = new SourceId(UUID.randomUUID());
        byte[] encoded = codec.encode(new Publication(UUID.randomUUID(), Set.of(source), List.of(source), 2, false));
        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThatThrownBy(() -> codec.decode(trailing)).hasMessageContaining("reason:invalidRecipe");
    }
}
