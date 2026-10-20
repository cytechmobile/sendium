package gr.cytech.sendium.core.storage.pending;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Versioned CBOR representation of accepted sources; has no filesystem or lifecycle side effects. */
public final class PendingRecordCodec {
    public static final int SCHEMA_VERSION = 1;
    private final ObjectMapper mapper = new ObjectMapper(CBORFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(256).build())
            .build());

    public byte[] encode(PendingSourceRecord record) {
        Objects.requireNonNull(record, "record");
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("sourceId", record.source().value().toString());
        envelope.put("disposition", record.disposition().name());
        envelope.set("message", record.message().toNode());
        try {
            return mapper.writeValueAsBytes(envelope);
        } catch (IOException failure) {
            throw PendingRecordFields.unsupported();
        }
    }

    public PendingSourceRecord decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw PendingRecordFields.corrupt();
        }
        try (var parser = mapper.createParser(bytes)) {
            JsonNode envelope = mapper.readTree(parser);
            if (envelope == null || !envelope.isObject() || parser.nextToken() != null) {
                throw PendingRecordFields.corrupt();
            }
            int version = PendingRecordFields.integer(PendingRecordFields.required(envelope, "schemaVersion"), 1, Integer.MAX_VALUE);
            if (version != SCHEMA_VERSION) {
                throw new PendingRecordException(PendingRecordException.Kind.UNSUPPORTED_VERSION);
            }
            String source = PendingRecordFields.nonNullText(PendingRecordFields.required(envelope, "sourceId"));
            UUID uuid = UUID.fromString(source);
            if (!uuid.toString().equalsIgnoreCase(source)) {
                throw PendingRecordFields.corrupt();
            }
            var disposition = PendingSourceRecord.Disposition.valueOf(
                    PendingRecordFields.nonNullText(PendingRecordFields.required(envelope, "disposition")));
            var message = PendingMessageData.fromNode(PendingRecordFields.required(envelope, "message"));
            return new PendingSourceRecord(new SourceId(uuid), disposition, message);
        } catch (IOException | IllegalArgumentException failure) {
            throw PendingRecordFields.corrupt();
        }
    }
}
