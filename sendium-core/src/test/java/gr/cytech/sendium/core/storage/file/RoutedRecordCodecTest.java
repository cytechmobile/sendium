package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.outbound.OutboundWork.Routed;
import gr.cytech.sendium.core.outbound.OutboundWork.WorkId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.file.RoutedRecordCodec.StoredWork;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.cbor.CBORMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutedRecordCodecTest {
    @TempDir Path root;

    @Test
    void rejectsInvalidDestinationAndOwnership() throws Exception {
        var assignment = FileRoutedWorkStoreTest.assignment(1, "provider-A");
        var work = new Routed<>(new WorkId(UUID.fromString("abcdef01-0000-0000-0000-000000000001")), assignment.selection(),
                assignment.destination().name(), assignment.destination().message());
        FileRoutedWorkStoreTest.writeRecord(root, work, assignment.sources());
        byte[] bytes = Files.readAllBytes(FileRoutedWorkStoreTest.stagePath(root, "routed", work.id()));
        var codec = new RoutedRecordCodec();
        var decoded = codec.decode(bytes);
        assertThat(decoded.work().id()).isEqualTo(work.id());
        assertThat(decoded.work().destination()).isEqualTo("provider-A");
        assertThat(decoded.sources()).isEqualTo(assignment.sources());
        assertThat(codec.decode(codec.encode(decoded)).work().message().body).isEqualTo(work.message().body);
        var terminal = new StoredWork(work, assignment.sources()).terminal();
        assertThat(codec.decodeTerminal(codec.encodeTerminal(terminal))).isEqualTo(terminal);
        var mapper = new CBORMapper();
        for (String corruption : new String[]{"blankDestination", "emptySources", "duplicateSource", "nonCanonicalId", "classField", "truncated"}) {
            var node = (ObjectNode) mapper.readTree(bytes);
            switch (corruption) {
                case "blankDestination" -> node.put("destination", " ");
                case "emptySources" -> node.withArray("sources").removeAll();
                case "duplicateSource" -> node.withArray("sources").add(FileRoutedWorkStoreTest.source(1).value().toString());
                case "nonCanonicalId" -> node.put("workId", work.id().value().toString().toUpperCase(java.util.Locale.ROOT));
                case "classField" -> node.put("@class", "java.lang.ProcessBuilder");
                case "truncated" -> node.remove("message");
                default -> throw new AssertionError(corruption);
            }
            byte[] invalid = mapper.writeValueAsBytes(node);
            assertThatThrownBy(() -> codec.decode(invalid)).isInstanceOf(OutboundStorageException.class)
                    .hasMessageNotContaining(work.message().body);
        }
        assertThatThrownBy(() -> codec.decodeTerminal(new byte[0])).isInstanceOf(OutboundStorageException.class);
    }
}
