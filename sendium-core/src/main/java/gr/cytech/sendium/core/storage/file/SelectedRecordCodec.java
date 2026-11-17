package gr.cytech.sendium.core.storage.file;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static gr.cytech.sendium.core.storage.OutboundStage.Role.ROUTER_QUEUE;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNSUPPORTED;

/** Explicit immutable selected execution, independent of accepted-record envelopes and runtime retries. */
final class SelectedRecordCodec {
    private final ObjectMapper mapper = new ObjectMapper(CBORFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(256).build()).build());

    byte[] encode(Selected<StandardMessage> selected) {
        validateSources(selected.sources(), selected.message());
        var node = mapper.createObjectNode();
        node.put("schemaVersion", 1);
        node.put("selectionId", selected.id().value().toString());
        var sources = node.putArray("sources");
        selected.sources().stream().map(SourceId::value).sorted().forEach(id -> sources.add(id.toString()));
        node.set("message", PendingMessageData.capture(selected.message()).toNode());
        try {
            return mapper.writeValueAsBytes(node);
        } catch (IOException failure) {
            throw invalid(failure);
        }
    }

    Selected<StandardMessage> decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw invalid(null);
        }
        try (var parser = mapper.createParser(bytes)) {
            JsonNode node = mapper.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null || node.size() != 4) {
                throw invalid(null);
            }
            JsonNode version = node.required("schemaVersion");
            if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() <= 0) {
                throw invalid(null);
            }
            if (version.intValue() != 1) {
                throw new OutboundStorageException(ROUTER_QUEUE, UNSUPPORTED, "selectedRecovery|error|reason:unsupportedVersion");
            }
            final SelectionId id = new SelectionId(uuid(node.required("selectionId")));
            JsonNode array = node.required("sources");
            if (!array.isArray()) {
                throw invalid(null);
            }
            var sources = new HashSet<SourceId>();
            for (JsonNode source : array) {
                if (!sources.add(new SourceId(uuid(source)))) {
                    throw invalid(null);
                }
            }
            StandardMessage message = PendingMessageData.fromNode(node.required("message")).restore();
            validateSources(sources, message);
            return new Selected<>(id, sources, message);
        } catch (IOException | IllegalArgumentException failure) {
            throw invalid(failure);
        } catch (OutboundStorageException failure) {
            throw new OutboundStorageException(ROUTER_QUEUE, failure.reason(), "selectedRecovery|error|reason:invalidEnvelope", failure);
        }
    }

    private static void validateSources(Set<SourceId> sources, StandardMessage message) {
        // The execution serial is the original representative even for an assembled multipart result.
        if (sources.isEmpty() || message.serial == null ||
                !sources.contains(new SourceId(UUID.fromString(message.serial)))) {
            throw invalid(null);
        }
        if (message.reassembledParts != null) {
            var originals = new HashSet<SourceId>();
            for (String part : message.reassembledParts) {
                SourceId source = new SourceId(UUID.fromString(part));
                if (!sources.contains(source) || !originals.add(source)) {
                    throw invalid(null);
                }
            }
            if (originals.isEmpty() || !message.reassembledParts.getFirst().equals(message.serial)) {
                throw invalid(null);
            }
        }
        PendingSpool.requirePriority(message.priority);
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
        return new OutboundStorageException(ROUTER_QUEUE, UNAVAILABLE, "selectedRecovery|error|reason:invalidEnvelope", cause);
    }
}
