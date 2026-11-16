package gr.cytech.sendium.core.storage.pending;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStorageException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static gr.cytech.sendium.core.storage.OutboundStage.Role.PENDING;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;

/** Disposable source-only execution recipe; accepted records remain the sole payload storage. */
public final class PendingPublicationCodec {
    private final ObjectMapper mapper = new ObjectMapper(CBORFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(256).build()).build());

    public byte[] encode(Publication publication) {
        ObjectNode node = mapper.createObjectNode();
        node.put("schemaVersion", 2);
        node.put("publicationId", publication.id().toString());
        var sources = node.putArray("sources");
        publication.sources().stream().map(SourceId::value).sorted().forEach(id -> sources.add(id.toString()));
        var originals = node.putArray("originals");
        publication.originals().forEach(source -> originals.add(source.value().toString()));
        node.put("priority", publication.priority());
        node.put("assembled", publication.assembled());
        try {
            return mapper.writeValueAsBytes(node);
        } catch (IOException failure) {
            throw invalid();
        }
    }

    public Publication decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw invalid();
        }
        try (var parser = mapper.createParser(bytes)) {
            JsonNode node = mapper.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null ||
                    PendingRecordFields.integer(PendingRecordFields.required(node, "schemaVersion"), 1, Integer.MAX_VALUE) != 2 ||
                    node.size() != 6) {
                throw invalid();
            }
            final UUID id = uuid(PendingRecordFields.nonNullText(PendingRecordFields.required(node, "publicationId")));
            var sources = PendingRecordFields.required(node, "sources");
            if (!sources.isArray()) {
                throw invalid();
            }
            var owned = new HashSet<SourceId>();
            for (var source : sources) {
                if (!owned.add(new SourceId(uuid(PendingRecordFields.nonNullText(source))))) {
                    throw invalid();
                }
            }
            var originals = PendingRecordFields.required(node, "originals");
            if (!originals.isArray() || originals.isEmpty() || originals.size() > 255) {
                throw invalid();
            }
            var ordered = new ArrayList<SourceId>();
            for (var original : originals) {
                ordered.add(new SourceId(uuid(PendingRecordFields.nonNullText(original))));
            }
            var assembled = PendingRecordFields.required(node, "assembled");
            if (!assembled.isBoolean()) {
                throw invalid();
            }
            return new Publication(id, owned, ordered,
                    PendingRecordFields.integer(PendingRecordFields.required(node, "priority"), 1, 3), assembled.booleanValue());
        } catch (IOException | IllegalArgumentException | PendingRecordException failure) {
            // Parser diagnostics can contain message values; expose only a fixed failure.
            throw invalid();
        }
    }

    private static UUID uuid(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) {
            throw invalid();
        }
        return id;
    }

    private static OutboundStorageException invalid() {
        return new OutboundStorageException(PENDING, UNAVAILABLE, "pendingPublicationRead|error|reason:invalidRecipe");
    }

    public record Publication(UUID id, Set<SourceId> sources, List<SourceId> originals, int priority, boolean assembled) {
        public Publication {
            Objects.requireNonNull(id, "id");
            sources = Set.copyOf(sources);
            originals = List.copyOf(originals);
            if (sources.isEmpty() || originals.isEmpty() || originals.size() > 255 ||
                    new HashSet<>(originals).size() != originals.size() || !sources.containsAll(originals) ||
                    priority < 1 || priority > 3 || (!assembled && originals.size() != 1)) {
                throw new IllegalArgumentException("pendingPublication|error|reason:invalidRecipe");
            }
        }

        public SourceId representative() {
            return sources.stream().min(java.util.Comparator.comparing(SourceId::value)).orElseThrow();
        }
    }
}
