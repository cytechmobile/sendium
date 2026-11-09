package gr.cytech.sendium.app.storage;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilePendingProfileTest {
    @TempDir
    Path root;

    @Test
    void explicitFileProfileRestoresSourcesBeforeAdmissionAndKeepsOneExclusiveOwner() throws Exception {
        var config = new SmallRyeConfigBuilder().withDefaultValue(MessageStorageProfile.PENDING_PATH, root.toString()).build();
        var profile = new MessageStorageProfile("file", "memory", "memory");
        var message = new StandardMessage();
        message.serial = UUID.randomUUID().toString();
        message.body = "accepted";
        var source = new SourceId(UUID.fromString(message.serial));
        try (var before = StandaloneOutboundPipeline.coordinator(profile, config)) {
            assertThat(before.pendingMultipartRecovery().orElseThrow().isReady()).isTrue();
            before.accept(source, message);
            assertThatThrownBy(() -> StandaloneOutboundPipeline.coordinator(profile, config))
                    .isInstanceOf(gr.cytech.sendium.core.storage.OutboundStorageException.class);
        }
        try (var after = StandaloneOutboundPipeline.coordinator(profile, config)) {
            assertThat(after.pendingMultipartRecovery().orElseThrow().isReady()).isTrue();
            assertThat(after.selectToRouter(1)).isEqualTo(1);
            var selected = after.takeForRouting(Duration.ZERO).orElseThrow();
            assertThat(selected.sources()).containsExactly(source);
            assertThat(selected.message().body).isEqualTo("accepted");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\n"})
    void fileProfileRequiresAnExplicitNonBlankPath(String value) {
        var config = new SmallRyeConfigBuilder().withDefaultValue(MessageStorageProfile.PENDING_PATH, value).build();
        assertThatThrownBy(() -> StandaloneOutboundPipeline.coordinator(new MessageStorageProfile("file", "memory", "memory"), config))
                .hasMessageContaining("reason:missingPendingPath");
    }

    @Test
    void absentPathFailsForFileButIsNotRequiredForMemory() {
        var config = new SmallRyeConfigBuilder().build();
        assertThatThrownBy(() -> StandaloneOutboundPipeline.coordinator(new MessageStorageProfile("file", "memory", "memory"), config))
                .hasMessageContaining("reason:missingPendingPath");
        try (var memory = StandaloneOutboundPipeline.coordinator(new MessageStorageProfile("memory", "memory", "memory"), config)) {
            assertThat(memory.pendingMultipartRecovery()).isEmpty();
        }
    }

    @Test
    void unsafePathFailsWithoutMemoryFallback() throws Exception {
        var path = Files.createFile(root.resolve("not-a-directory"));
        var config = new SmallRyeConfigBuilder().withDefaultValue(MessageStorageProfile.PENDING_PATH, path.toString()).build();
        assertThatThrownBy(() -> StandaloneOutboundPipeline.coordinator(new MessageStorageProfile("file", "memory", "memory"), config))
                .isInstanceOf(gr.cytech.sendium.core.storage.OutboundStorageException.class);
    }
}
