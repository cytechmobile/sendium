package gr.cytech.sendium.app.storage;

import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageStorageProfileTest {
    @Test
    void missingSelectorsUseMemoryDefaults() {
        assertThat(profile(Map.of()).toString()).isEqualTo("memory/memory/memory");
    }

    @Test
    void explicitMemoryIsAccepted() {
        assertThat(profile(Map.of(
                MessageStorageProfile.PENDING_BACKEND, "memory",
                MessageStorageProfile.ROUTER_BACKEND, "memory",
                MessageStorageProfile.ROUTED_BACKEND, "memory")))
                .isEqualTo(new MessageStorageProfile("memory", "memory", "memory"));
    }

    @ParameterizedTest
    @MethodSource("unsupportedProfiles")
    void rejectsEveryUnimplementedCombination(String pending, String router, String routed) {
        assertThatThrownBy(() -> profile(Map.of(
                MessageStorageProfile.PENDING_BACKEND, pending,
                MessageStorageProfile.ROUTER_BACKEND, router,
                MessageStorageProfile.ROUTED_BACKEND, routed)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'" + pending + "/" + router + "/" + routed + "'")
                .hasMessageContaining("Supported profiles: memory/memory/memory")
                .hasMessageContaining(MessageStorageProfile.PENDING_BACKEND)
                .hasMessageContaining(MessageStorageProfile.ROUTER_BACKEND)
                .hasMessageContaining(MessageStorageProfile.ROUTED_BACKEND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "disk", "MEMORY"})
    void explicitInvalidValuesNeverUseTheDefault(String value) {
        for (String key : List.of(MessageStorageProfile.PENDING_BACKEND,
                MessageStorageProfile.ROUTER_BACKEND, MessageStorageProfile.ROUTED_BACKEND)) {
            assertThatThrownBy(() -> profile(Map.of(key, value)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported outbound message storage profile");
        }
    }

    private static Stream<Arguments> unsupportedProfiles() {
        List<String> backends = List.of("memory", "file", "postgresql");
        return backends.stream().flatMap(pending -> backends.stream().flatMap(router -> backends.stream()
                .filter(routed -> !List.of(pending, router, routed).equals(List.of("memory", "memory", "memory")))
                .map(routed -> Arguments.of(pending, router, routed))));
    }

    private static MessageStorageProfile profile(Map<String, String> settings) {
        var config = new SmallRyeConfigBuilder().withSources(new ConfigSource() {
            @Override
            public Map<String, String> getProperties() {
                return settings;
            }

            @Override
            public Set<String> getPropertyNames() {
                return settings.keySet();
            }

            @Override
            public String getValue(String propertyName) {
                return settings.get(propertyName);
            }

            @Override
            public String getName() {
                return "profile-test";
            }
        }).build();
        return new StandaloneMessageStorage().profile(config);
    }
}
