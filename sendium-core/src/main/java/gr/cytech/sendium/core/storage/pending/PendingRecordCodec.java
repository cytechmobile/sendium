package gr.cytech.sendium.core.storage.pending;

import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.cbor.CBORFactory;
import tools.jackson.dataformat.cbor.CBORMapper;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Versioned CBOR representation of accepted sources; has no filesystem or lifecycle side effects. */
public final class PendingRecordCodec {
    public static final int SCHEMA_VERSION = 1;
    private final ObjectMapper mapper = CBORMapper.builder(CBORFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(256).build())
            .build()).build();

    public byte[] encode(PendingSourceRecord record) {
        Objects.requireNonNull(record, "record");
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("sourceId", record.source().value().toString());
        envelope.put("disposition", record.disposition().name());
        envelope.set("message", record.message().toNode());
        envelope.set("multipart", membership(record.multipart()));
        try {
            return mapper.writeValueAsBytes(envelope);
        } catch (JacksonException failure) {
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
            UUID uuid = uuid(PendingRecordFields.required(envelope, "sourceId"));
            var disposition = PendingSourceRecord.Disposition.valueOf(
                    PendingRecordFields.nonNullText(PendingRecordFields.required(envelope, "disposition")));
            var message = PendingMessageData.fromNode(PendingRecordFields.required(envelope, "message"));
            var multipart = readMembership(PendingRecordFields.required(envelope, "multipart"));
            return new PendingSourceRecord(new SourceId(uuid), disposition, message, multipart);
        } catch (JacksonException | IllegalArgumentException | DateTimeException failure) {
            throw PendingRecordFields.corrupt();
        }
    }

    private JsonNode membership(MultipartMembership part) {
        if (part == null) {
            return mapper.nullNode();
        }
        ObjectNode node = mapper.createObjectNode();
        node.put("sourceId", part.source().value().toString());
        node.put("originalSourceId", part.originalSource().value().toString());
        node.put("groupId", part.groupId().toString());
        node.put("ordinal", part.ordinal());
        node.put("deadline", part.deadline().toString());
        node.put("groupOrder", part.groupOrder());
        ObjectNode key = node.putObject("key");
        key.put("accountId", part.key().accountId());
        key.put("systemId", part.key().systemId());
        key.put("sender", part.key().sender());
        key.put("recipient", part.key().recipient());
        key.put("referenceBits", part.key().referenceBits());
        key.put("reference", part.key().reference());
        key.put("total", part.key().total());
        key.put("normalizedUdh", part.key().normalizedUdh());
        return node;
    }

    private static MultipartMembership readMembership(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        JsonNode key = PendingRecordFields.required(node, "key");
        var identity = new MultipartKey(
                PendingRecordFields.text(PendingRecordFields.required(key, "accountId")),
                PendingRecordFields.text(PendingRecordFields.required(key, "systemId")),
                PendingRecordFields.text(PendingRecordFields.required(key, "sender")),
                PendingRecordFields.text(PendingRecordFields.required(key, "recipient")),
                PendingRecordFields.integer(PendingRecordFields.required(key, "referenceBits"), 8, 16),
                PendingRecordFields.integer(PendingRecordFields.required(key, "reference"), 0, 65535),
                PendingRecordFields.integer(PendingRecordFields.required(key, "total"), 1, 255),
                PendingRecordFields.nonNullText(PendingRecordFields.required(key, "normalizedUdh")));
        return new MultipartMembership(new SourceId(uuid(PendingRecordFields.required(node, "sourceId"))),
                uuid(PendingRecordFields.required(node, "groupId")), identity,
                PendingRecordFields.integer(PendingRecordFields.required(node, "ordinal"), 1, identity.total()),
                Instant.parse(PendingRecordFields.nonNullText(PendingRecordFields.required(node, "deadline"))),
                PendingRecordFields.integer(PendingRecordFields.required(node, "groupOrder")),
                new SourceId(uuid(PendingRecordFields.required(node, "originalSourceId"))));
    }

    private static UUID uuid(JsonNode node) {
        String value = PendingRecordFields.nonNullText(node);
        UUID result = UUID.fromString(value);
        if (!result.toString().equalsIgnoreCase(value)) {
            throw PendingRecordFields.corrupt();
        }
        return result;
    }
}
