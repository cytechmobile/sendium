package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.pending.PendingPublicationCodec;
import gr.cytech.sendium.core.storage.pending.PendingPublicationCodec.Publication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;

/** First-binding execution state for this process only. The entire directory is discarded on open. */
final class HeldPendingPublications {
    private final Path root;
    private final PendingPublicationCodec codec = new PendingPublicationCodec();

    HeldPendingPublications(Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root.resolve("work"));
        Files.createDirectories(root.resolve("bindings"));
    }

    void publish(Publication publication, byte[] encoded) throws IOException {
        Files.write(work(publication.id()), encoded, StandardOpenOption.CREATE_NEW);
        for (SourceId source : publication.sources()) {
            Path binding = binding(source);
            Files.createDirectories(binding.getParent());
            Files.writeString(binding, publication.id().toString(), StandardOpenOption.CREATE_NEW);
        }
    }

    byte[] encode(Publication publication) {
        return codec.encode(publication);
    }

    void remove(Publication publication) throws IOException {
        for (SourceId source : publication.sources()) {
            Optional<UUID> current = bindingId(source);
            if (current.isPresent()) {
                if (!current.orElseThrow().equals(publication.id())) {
                    throw new IOException("pendingPublicationCleanup|error|reason:bindingMismatch");
                }
                Files.delete(binding(source));
            }
        }
        requireFile(work(publication.id()));
        Files.delete(work(publication.id()));
    }

    Optional<Publication> find(SourceId source) throws IOException {
        Optional<UUID> binding = bindingId(source);
        if (binding.isEmpty()) {
            return Optional.empty();
        }
        UUID id = binding.orElseThrow();
        Path work = work(id);
        requireFile(work);
        Publication publication = codec.decode(Files.readAllBytes(work));
        if (!publication.id().equals(id) || !publication.sources().contains(source)) {
            throw new IOException("pendingPublicationRead|error|reason:bindingMismatch");
        }
        return Optional.of(publication);
    }

    Optional<UUID> bindingId(SourceId source) throws IOException {
        Path path = binding(source);
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        requireFile(path);
        UUID id;
        try {
            String text = Files.readString(path);
            id = UUID.fromString(text);
            if (!id.toString().equals(text)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException failure) {
            throw new IOException("pendingPublicationRead|error|reason:invalidBinding");
        }
        return Optional.of(id);
    }

    private Path work(UUID id) {
        return root.resolve("work").resolve(id + ".cbor");
    }

    private Path binding(SourceId source) {
        String id = source.value().toString();
        return root.resolve("bindings").resolve(id.substring(0, 2)).resolve(id);
    }

    private static void requireFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("pendingPublicationRead|error|reason:regularFileRequired");
        }
    }
}
