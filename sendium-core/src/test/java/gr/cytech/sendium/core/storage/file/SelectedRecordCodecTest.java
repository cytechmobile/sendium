package gr.cytech.sendium.core.storage.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SelectedRecordCodecTest {
    @TempDir
    Path root;

    @Test
    void rejectsInvalidSelectedEnvelopeWithoutClassLoading() throws Exception {
        var message = FileSelectedRouterStoreTest.message(1, 2);
        var original = new Selected<>(new SelectionId(UUID.fromString("abcdef01-0000-0000-0000-000000000001")),
                Set.of(FileSelectedRouterStoreTest.source(1)), message);
        FileSelectedRouterStoreTest.writeSelected(root, original);
        byte[] bytes = Files.readAllBytes(FileSelectedRouterStoreTest.selectedPath(root, original.id()));
        var codec = new SelectedRecordCodec();
        var decoded = codec.decode(bytes);
        assertThat(decoded.id()).isEqualTo(original.id());
        assertThat(decoded.message().body).isEqualTo(message.body);
        assertThat(codec.decode(codec.encode(decoded)).sources()).isEqualTo(original.sources());
        var mapper = new ObjectMapper(new CBORFactory());
        var node = (ObjectNode) mapper.readTree(bytes);
        node.withArray("sources").removeAll().add(FileSelectedRouterStoreTest.source(2).value().toString());
        byte[] mismatch = mapper.writeValueAsBytes(node);
        assertThatThrownBy(() -> codec.decode(mismatch)).isInstanceOf(OutboundStorageException.class);
        node = (ObjectNode) mapper.readTree(bytes);
        node.put("@class", "java.lang.ProcessBuilder");
        byte[] classEnvelope = mapper.writeValueAsBytes(node);
        assertThatThrownBy(() -> codec.decode(classEnvelope)).isInstanceOf(OutboundStorageException.class);
        node = (ObjectNode) mapper.readTree(bytes);
        node.put("selectionId", original.id().value().toString().toUpperCase(java.util.Locale.ROOT));
        byte[] nonCanonical = mapper.writeValueAsBytes(node);
        assertThatThrownBy(() -> codec.decode(nonCanonical)).isInstanceOf(OutboundStorageException.class);
        assertThatThrownBy(() -> codec.decode(new byte[0])).isInstanceOf(OutboundStorageException.class);
    }
}
