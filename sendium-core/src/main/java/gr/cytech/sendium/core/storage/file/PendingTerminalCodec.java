package gr.cytech.sendium.core.storage.file;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStorageException;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static gr.cytech.sendium.core.storage.OutboundStage.Role.PENDING;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNSUPPORTED;

/** Metadata for an already-authorized terminal removal, not a provider-outcome checkpoint. */
final class PendingTerminalCodec {
    private final ObjectMapper mapper = new ObjectMapper(CBORFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).build()).build());

    byte[] encode(Intent intent) {
        var node = mapper.createObjectNode();
        node.put("schemaVersion", 1);
        node.put("intentId", intent.id().toString());
        var sources = node.putArray("sources");
        intent.sources().stream().map(SourceId::value).sorted().forEach(id -> sources.add(id.toString()));
        var groups = node.putArray("closedGroups");
        intent.closedGroups().stream().sorted().forEach(id -> groups.add(id.toString()));
        try {
            return mapper.writeValueAsBytes(node);
        } catch (IOException failure) {
            throw invalid();
        }
    }

    Intent decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw invalid();
        }
        try (var parser = mapper.createParser(bytes)) {
            JsonNode node = mapper.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null) {
                throw invalid();
            }
            JsonNode version = node.required("schemaVersion");
            if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() <= 0) {
                throw invalid();
            }
            if (version.intValue() != 1) {
                throw new OutboundStorageException(PENDING, UNSUPPORTED, "pendingTerminalRecovery|error|reason:unsupportedIntentVersion");
            }
            return new Intent(uuid(node.required("intentId")),
                    ids(node.required("sources")).stream().map(SourceId::new).collect(Collectors.toSet()),
                    ids(node.required("closedGroups")));
        } catch (IOException | IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static Set<UUID> ids(JsonNode node) {
        if (!node.isArray()) {
            throw invalid();
        }
        var ids = new HashSet<UUID>();
        for (JsonNode value : node) {
            if (!ids.add(uuid(value))) {
                throw invalid();
            }
        }
        return Set.copyOf(ids);
    }

    private static UUID uuid(JsonNode node) {
        if (!node.isTextual()) {
            throw invalid();
        }
        UUID id = UUID.fromString(node.textValue());
        if (!id.toString().equals(node.textValue())) {
            throw invalid();
        }
        return id;
    }

    private static OutboundStorageException invalid() {
        return new OutboundStorageException(PENDING, UNAVAILABLE, "pendingTerminalRecovery|error|reason:invalidIntent");
    }

    record Intent(UUID id, Set<SourceId> sources, Set<UUID> closedGroups) {
        Intent {
            Objects.requireNonNull(id, "id");
            sources = Set.copyOf(sources);
            closedGroups = Set.copyOf(closedGroups);
            if (sources.isEmpty()) {
                throw new IllegalArgumentException("pendingCompletion|error|reason:emptyIntent");
            }
        }
    }
}
