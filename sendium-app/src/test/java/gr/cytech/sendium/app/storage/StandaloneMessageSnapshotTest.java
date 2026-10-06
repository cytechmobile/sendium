package gr.cytech.sendium.app.storage;

import gr.cytech.sendium.core.message.StandardMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StandaloneMessageSnapshotTest {
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
