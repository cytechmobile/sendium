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

class SmsStorageProfileTest {
    @Test
    void missingSelectorsUseMemoryDefaults() {
        assertThat(profile(Map.of()).toString()).isEqualTo("memory/memory/memory");
    }

    @Test
    void explicitMemoryIsAccepted() {
        assertThat(profile(Map.of(
                SmsStorageProfile.PENDING_BACKEND, "memory",
                SmsStorageProfile.ROUTER_BACKEND, "memory",
                SmsStorageProfile.ROUTED_BACKEND, "memory")))
                .isEqualTo(new SmsStorageProfile("memory", "memory", "memory"));
    }

    @ParameterizedTest
    @MethodSource("unsupportedProfiles")
    void rejectsEveryUnimplementedCombination(String pending, String router, String routed) {
        assertThatThrownBy(() -> profile(Map.of(
                SmsStorageProfile.PENDING_BACKEND, pending,
                SmsStorageProfile.ROUTER_BACKEND, router,
                SmsStorageProfile.ROUTED_BACKEND, routed)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'" + pending + "/" + router + "/" + routed + "'")
                .hasMessageContaining("Supported profiles: memory/memory/memory")
                .hasMessageContaining(SmsStorageProfile.PENDING_BACKEND)
                .hasMessageContaining(SmsStorageProfile.ROUTER_BACKEND)
                .hasMessageContaining(SmsStorageProfile.ROUTED_BACKEND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "disk", "MEMORY"})
    void explicitInvalidValuesNeverUseTheDefault(String value) {
        for (String key : List.of(SmsStorageProfile.PENDING_BACKEND,
                SmsStorageProfile.ROUTER_BACKEND, SmsStorageProfile.ROUTED_BACKEND)) {
            assertThatThrownBy(() -> profile(Map.of(key, value)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported outbound SMS storage profile");
        }
    }

    private static Stream<Arguments> unsupportedProfiles() {
        List<String> backends = List.of("memory", "file", "postgresql");
        return backends.stream().flatMap(pending -> backends.stream().flatMap(router -> backends.stream()
                .filter(routed -> !List.of(pending, router, routed).equals(List.of("memory", "memory", "memory")))
                .map(routed -> Arguments.of(pending, router, routed))));
    }

    private static SmsStorageProfile profile(Map<String, String> settings) {
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
        return new StandaloneSmsStorage().profile(config);
    }
}
