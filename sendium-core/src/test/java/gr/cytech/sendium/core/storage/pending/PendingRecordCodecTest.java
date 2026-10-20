package gr.cytech.sendium.core.storage.pending;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static gr.cytech.sendium.core.storage.OutboundStage.Role.PENDING;
import static gr.cytech.sendium.core.storage.pending.PendingRecordMappingTest.message;
import static gr.cytech.sendium.core.storage.pending.PendingRecordMappingTest.source;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingRecordCodecTest {
    private final PendingRecordCodec codec = new PendingRecordCodec();
    private final ObjectMapper cbor = new ObjectMapper(new CBORFactory());

    @Test
    void encodesSupportedExtensionsDeterministically() {
        StandardMessage first = message();
        var values = new ArrayList<Object>();
        values.add(null);
        values.addAll(List.of("text", true, (byte) 1, (short) 2, 3, 4L, 1.25f, 2.5d, new byte[]{0, (byte) 255}));
        var nested = new LinkedHashMap<String, Object>();
        nested.put("z", values);
        nested.put("a", "nested");
        first.attrs = new LinkedHashMap<>();
        first.attrs.put("b", 2L);
        first.attrs.put("a", 1);
        first.attrs.put("nested", nested);
        StandardMessage second = message();
        second.attrs = new LinkedHashMap<>();
        second.attrs.put("nested", new LinkedHashMap<>(Map.of("a", "nested", "z", values)));
        second.attrs.put("a", 1);
        second.attrs.put("b", 2L);
        // Match capture timestamps; defaults must not make otherwise equal records differ.
        second.expr = first.expr;
        second.ctstamp = first.ctstamp;
        second.sutstamp = first.sutstamp;

        byte[] encoded = codec.encode(source(first));
        assertThat(encoded).isEqualTo(codec.encode(source(second)));
        StandardMessage restored = codec.decode(encoded).message().restore();
        assertThat(restored.attrs.get("a")).isExactlyInstanceOf(Integer.class).isEqualTo(1);
        assertThat(restored.attrs.get("b")).isExactlyInstanceOf(Long.class).isEqualTo(2L);
        assertThat(restored.attrs).usingRecursiveComparison().isEqualTo(first.attrs);
    }

    @Test
    void rejectsUnknownVersion() throws Exception {
        ObjectNode record = encodedTree();
        record.put("schemaVersion", 999);
        assertFailure(cbor.writeValueAsBytes(record), "UNSUPPORTED_VERSION");
    }

    @Test
    void rejectsMalformedRecords() throws Exception {
        byte[] encoded = codec.encode(source(message()));
        for (int length = 0; length < encoded.length; length++) {
            assertFailure(Arrays.copyOf(encoded, length), "CORRUPT_RECORD");
        }
        assertFailure(new byte[]{(byte) 255}, "CORRUPT_RECORD");
        assertFailure(cbor.writeValueAsBytes(List.of("not-an-envelope")), "CORRUPT_RECORD");
        for (String field : List.of("schemaVersion", "sourceId", "disposition", "message")) {
            ObjectNode record = encodedTree();
            record.remove(field);
            assertFailure(cbor.writeValueAsBytes(record), "CORRUPT_RECORD");
        }
        for (String field : List.of("priority", "body", "dcs", "dlrReturnMetadata")) {
            ObjectNode record = encodedTree();
            ((ObjectNode) record.get("message")).remove(field);
            assertFailure(cbor.writeValueAsBytes(record), "CORRUPT_RECORD");
        }
        for (Object replacement : List.of(1.5, "wrong", true)) {
            ObjectNode record = encodedTree();
            ((ObjectNode) record.get("message")).set("priority", cbor.valueToTree(replacement));
            assertFailure(cbor.writeValueAsBytes(record), "CORRUPT_RECORD");
        }
        ObjectNode overflow = encodedTree();
        ((ObjectNode) overflow.get("message")).put("dcs", 256);
        assertFailure(cbor.writeValueAsBytes(overflow), "CORRUPT_RECORD");
        ObjectNode mismatch = encodedTree();
        mismatch.put("sourceId", "6634ad89-7b7e-4b94-9328-3b982d3e0899");
        assertFailure(cbor.writeValueAsBytes(mismatch), "CORRUPT_RECORD");
        ObjectNode unknownDisposition = encodedTree();
        unknownDisposition.put("disposition", "invented");
        assertFailure(cbor.writeValueAsBytes(unknownDisposition), "CORRUPT_RECORD");
        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        trailing[trailing.length - 1] = 1;
        assertFailure(trailing, "CORRUPT_RECORD");
    }

    @Test
    void rejectsArbitraryClassReconstruction() throws Exception {
        ObjectNode record = encodedTree();
        ObjectNode payload = (ObjectNode) record.get("message");
        ObjectNode malicious = cbor.createObjectNode();
        malicious.put("type", "java.lang.ProcessBuilder");
        malicious.put("value", "private-extension");
        payload.set("field1", malicious);
        assertFailure(cbor.writeValueAsBytes(record), "CORRUPT_RECORD");
    }

    @Test
    void redactsInvalidPayloadDiagnostics() throws Exception {
        ObjectNode record = encodedTree();
        ((ObjectNode) record.get("message")).put("priority", "private-body-and-extension");
        try {
            codec.decode(cbor.writeValueAsBytes(record));
            throw new AssertionError("Expected corrupt record");
        } catch (OutboundStorageException failure) {
            assertThat(failure.stage()).isEqualTo(PENDING);
            assertThat(((PendingRecordException) failure).kind()).isEqualTo(PendingRecordException.Kind.CORRUPT_RECORD);
            assertThat(failure.getMessage()).doesNotContain("private", "body-and-extension");
            assertThat(failure.getCause()).isNull();
        }
        byte[] invalidText = cbor.writeValueAsBytes("private-body-and-extension");
        assertThatThrownBy(() -> codec.decode(Arrays.copyOf(invalidText, invalidText.length - 1)))
                .isInstanceOf(OutboundStorageException.class)
                .hasNoCause()
                .hasMessageNotContaining("private");
    }

    @Test
    void rejectsUnsupportedExtensionValues() {
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        Map<Object, Object> invalidKeys = new HashMap<>();
        invalidKeys.put(7, "private");
        for (Object value : List.of(new Object(), Thread.State.NEW, cyclic, invalidKeys, Float.NaN, Double.POSITIVE_INFINITY)) {
            StandardMessage message = message();
            message.field1 = value;
            assertThatThrownBy(() -> codec.encode(source(message)))
                    .isInstanceOfSatisfying(OutboundStorageException.class,
                            failure -> assertThat(failure.reason().name()).isEqualTo("UNSUPPORTED"))
                    .hasNoCause()
                    .hasMessageNotContaining("private");
        }
    }

    @Test
    void acceptsUnknownOptionalFieldsWithinKnownVersion() throws Exception {
        ObjectNode record = encodedTree();
        record.put("futureOptionalNote", "ignored");
        ((ObjectNode) record.get("message")).put("futureOptionalInput", 17);
        assertThat(codec.decode(cbor.writeValueAsBytes(record)).message().restore().body).isEqualTo("private body");
    }

    private ObjectNode encodedTree() throws Exception {
        return (ObjectNode) cbor.readTree(codec.encode(source(message())));
    }

    private void assertFailure(byte[] input, String reason) {
        assertThatThrownBy(() -> codec.decode(input)).isInstanceOfSatisfying(OutboundStorageException.class, failure -> {
            assertThat(failure.stage()).isEqualTo(PENDING);
            assertThat(((PendingRecordException) failure).kind().name()).isEqualTo(reason);
            assertThat(failure.getCause()).isNull();
        });
    }
}
