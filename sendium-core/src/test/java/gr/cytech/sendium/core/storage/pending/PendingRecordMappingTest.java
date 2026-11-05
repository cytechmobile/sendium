package gr.cytech.sendium.core.storage.pending;

import gr.cytech.sendium.core.message.DlrReturnMetadata;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingRecordMappingTest {
    private final PendingRecordCodec codec = new PendingRecordCodec();

    static Stream<Field> messageFields() {
        return Stream.of(StandardMessage.class.getFields()).filter(field -> !Modifier.isStatic(field.getModifiers()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("messageFields")
    void roundTripsEveryPreservedField(Field field) throws Exception {
        StandardMessage message = message();
        Object sample = sample(field);
        field.set(message, sample);
        var source = source(message);

        var recovered = codec.decode(codec.encode(source));

        assertThat(recovered.source()).isEqualTo(source.source());
        assertThat(recovered.disposition()).isEqualTo(source.disposition());
        assertThat(field.get(recovered.message().restore())).usingRecursiveComparison().isEqualTo(sample);
    }

    @Test
    void sourceIdentityMustMatchGatewayIdentity() {
        assertThatThrownBy(() -> new PendingSourceRecord(new SourceId(UUID.randomUUID()),
                PendingSourceRecord.Disposition.READY, PendingMessageData.capture(message())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void capturedDataIsDetachedFromMutableInputsAndRestoredMessages() {
        StandardMessage message = message();
        byte[] bytes = {1, 2};
        var list = new ArrayList<Object>(List.of("first", bytes));
        message.attrs = new HashMap<>(Map.of("items", list));
        message.tlvs = new HashMap<>(Map.of("0427", "1"));
        message.reassembledParts = new ArrayList<>(List.of("source-a"));
        PendingMessageData data = PendingMessageData.capture(message);
        bytes[0] = 9;
        list.clear();
        message.tlvs.clear();
        message.reassembledParts.clear();

        StandardMessage restored = data.restore();
        assertThat((List<?>) restored.attrs.get("items")).hasSize(2);
        assertThat((byte[]) ((List<?>) restored.attrs.get("items")).get(1)).containsExactly(1, 2);
        restored.attrs.clear();
        restored.tlvs.clear();
        restored.reassembledParts.clear();
        assertThat(data.restore().attrs).containsKey("items");
        assertThat(data.restore().tlvs).containsEntry("0427", "1");
        assertThat(data.restore().reassembledParts).containsExactly("source-a");
    }

    @Test
    void preservesNullCollectionsAndBothDlrChannels() {
        for (var metadata : List.of(DlrReturnMetadata.http("account", "sender", "recipient", "https://example.test/dlr"),
                DlrReturnMetadata.smpp("account", "system", "sender", "recipient"))) {
            StandardMessage message = message();
            message.dlrReturnMetadata = metadata;
            StandardMessage restored = codec.decode(codec.encode(source(message))).message().restore();
            assertThat(restored.dlrReturnMetadata).isEqualTo(metadata);
            assertThat(restored.attrs).isNull();
            assertThat(restored.tlvs).isNull();
            assertThat(restored.reassembledParts).isNull();
        }
    }

    static StandardMessage message() {
        StandardMessage message = new StandardMessage();
        message.serial = "6634ad89-7b7e-4b94-9328-3b982d3e0898";
        message.from = "sender";
        message.to = "recipient";
        message.body = "private body";
        return message;
    }

    static PendingSourceRecord source(StandardMessage message) {
        return new PendingSourceRecord(new SourceId(UUID.fromString(message.serial)),
                PendingSourceRecord.Disposition.READY, PendingMessageData.capture(message));
    }

    private static Object sample(Field field) {
        return switch (field.getName()) {
            case "serial" -> message().serial;
            case "attrs" -> new HashMap<>(Map.of("routing-input", List.of("value", 7L)));
            case "tlvs" -> new HashMap<>(Map.of("0427", "1"));
            case "reassembledParts" -> new ArrayList<>(List.of("original-a", "original-b"));
            case "dlrReturnMetadata" -> DlrReturnMetadata.http("account", "sender", "recipient", "https://example.test/dlr");
            case "marginStatus" -> StandardMessage.MarginStatus.KNOWN;
            default -> {
                Class<?> type = field.getType();
                if (type == String.class) {
                    yield "value-for-" + field.getName();
                }
                if (type == int.class) {
                    yield 37;
                }
                if (type == long.class) {
                    yield 9_000_000_001L;
                }
                if (type == byte.class) {
                    yield (byte) -7;
                }
                if (type == boolean.class) {
                    yield true;
                }
                if (type == double.class) {
                    yield 1.25d;
                }
                if (type == Object.class) {
                    yield new ArrayList<>(List.of("extension", (short) 4, new byte[]{0, (byte) 255}));
                }
                throw new AssertionError("Unmapped field: " + field.getName());
            }
        };
    }
}
