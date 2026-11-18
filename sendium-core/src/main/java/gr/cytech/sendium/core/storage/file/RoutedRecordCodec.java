package gr.cytech.sendium.core.storage.file;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Completed;
import gr.cytech.sendium.core.outbound.OutboundWork.Routed;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.outbound.OutboundWork.WorkId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static gr.cytech.sendium.core.storage.OutboundStage.Role.ROUTED_WORK;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNSUPPORTED;

/** Immutable routing-time payload and a separate final processing fact, never provider-part checkpoints. */
final class RoutedRecordCodec {
    private final ObjectMapper mapper = new ObjectMapper(CBORFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(256).build()).build());

    byte[] encode(StoredWork record) {
        ObjectNode node = identity(record.work().id(), record.work().selection(), record.sources());
        node.put("destination", record.work().destination());
        node.set("message", PendingMessageData.capture(record.work().message()).toNode());
        return bytes(node);
    }

    StoredWork decode(byte[] bytes) {
        try {
            JsonNode node = envelope(bytes, 6);
            final WorkId id = new WorkId(uuid(node.required("workId")));
            final SelectionId selection = new SelectionId(uuid(node.required("selectionId")));
            Set<SourceId> sources = sources(node.required("sources"));
            JsonNode destination = node.required("destination");
            if (!destination.isTextual() || destination.textValue().isBlank()) {
                throw invalid(null);
            }
            StandardMessage message = PendingMessageData.fromNode(node.required("message")).restore();
            return new StoredWork(new Routed<>(id, selection, destination.textValue(), message), sources);
        } catch (IllegalArgumentException failure) {
            throw invalid(failure);
        } catch (OutboundStorageException failure) {
            throw new OutboundStorageException(ROUTED_WORK, failure.reason(), "routedRecovery|error|reason:invalidEnvelope", failure);
        }
    }

    byte[] encodeTerminal(Terminal terminal) {
        return bytes(identity(terminal.id(), terminal.selection(), terminal.sources()));
    }

    Terminal decodeTerminal(byte[] bytes) {
        JsonNode node = envelope(bytes, 4);
        try {
            return new Terminal(new WorkId(uuid(node.required("workId"))),
                    new SelectionId(uuid(node.required("selectionId"))), sources(node.required("sources")));
        } catch (IllegalArgumentException failure) {
            throw invalid(failure);
        }
    }

    private ObjectNode identity(WorkId id, SelectionId selection, Set<SourceId> sources) {
        ObjectNode node = mapper.createObjectNode();
        node.put("schemaVersion", 1);
        node.put("workId", id.value().toString());
        node.put("selectionId", selection.value().toString());
        var array = node.putArray("sources");
        sources.stream().map(SourceId::value).sorted().forEach(source -> array.add(source.toString()));
        return node;
    }

    private byte[] bytes(JsonNode node) {
        try {
            return mapper.writeValueAsBytes(node);
        } catch (IOException failure) {
            throw invalid(failure);
        }
    }

    private JsonNode envelope(byte[] bytes, int fields) {
        if (bytes == null || bytes.length == 0) {
            throw invalid(null);
        }
        try (var parser = mapper.createParser(bytes)) {
            JsonNode node = mapper.readTree(parser);
            if (node == null || !node.isObject() || node.size() != fields || parser.nextToken() != null) {
                throw invalid(null);
            }
            JsonNode version = node.required("schemaVersion");
            if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() <= 0) {
                throw invalid(null);
            }
            if (version.intValue() != 1) {
                throw new OutboundStorageException(ROUTED_WORK, UNSUPPORTED, "routedRecovery|error|reason:unsupportedVersion");
            }
            return node;
        } catch (IOException | IllegalArgumentException failure) {
            throw invalid(failure);
        }
    }

    private static Set<SourceId> sources(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            throw invalid(null);
        }
        var result = new HashSet<SourceId>();
        for (JsonNode value : node) {
            if (!result.add(new SourceId(uuid(value)))) {
                throw invalid(null);
            }
        }
        return Set.copyOf(result);
    }

    private static UUID uuid(JsonNode node) {
        if (!node.isTextual()) {
            throw invalid(null);
        }
        UUID id = UUID.fromString(node.textValue());
        if (!id.toString().equals(node.textValue())) {
            throw invalid(null);
        }
        return id;
    }

    private static OutboundStorageException invalid(Throwable cause) {
        return new OutboundStorageException(ROUTED_WORK, UNAVAILABLE, "routedRecovery|error|reason:invalidEnvelope", cause);
    }

    record StoredWork(Routed<StandardMessage> work, Set<SourceId> sources) {
        StoredWork {
            Objects.requireNonNull(work, "work");
            sources = Set.copyOf(sources);
            if (sources.isEmpty()) {
                throw invalid(null);
            }
        }

        Terminal terminal() {
            return new Terminal(work.id(), work.selection(), sources);
        }
    }

    record Terminal(WorkId id, SelectionId selection, Set<SourceId> sources) {
        Terminal {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(selection, "selection");
            sources = Set.copyOf(sources);
            if (sources.isEmpty()) {
                throw invalid(null);
            }
        }

        Completed completed() {
            return new Completed(selection, sources);
        }
    }
}
