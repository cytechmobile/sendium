package gr.cytech.sendium.app.dlr;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PostgreSQL datasource activation is derived from the subsystem flag and backend selector with plain property
 * expressions. Only the enabled postgresql default activates it; nothing falls back to an active datasource.
 */
class DlrProfileValidationTest {
    private static final Path APP = Path.of("src/main/resources/application.properties");
    private static final Path CORE = Path.of("../sendium-core/src/main/resources/application.properties");

    @ParameterizedTest(name = "app backend={0}")
    @CsvSource(nullValues = "-", value = {
        "-, true", "postgresql, true", "file, false", "memory, false", "unknown, false", "FILE, false"})
    void standaloneDatasourceFollowsBackend(String backend, boolean active) throws Exception {
        Map<String, String> overrides = new HashMap<>();
        if (backend != null) {
            overrides.put("sendium.dlr.storage.backend", backend);
        }
        SmallRyeConfig config = config(APP, overrides);
        assertThat(config.getValue("quarkus.datasource.dlr.active", Boolean.class)).isEqualTo(active);
        assertThat(config.getValue("quarkus.flyway.dlr.active", Boolean.class)).isEqualTo(active);
        assertThat(config.getValue("quarkus.flyway.dlr.migrate-at-start", Boolean.class)).isEqualTo(active);
    }

    @ParameterizedTest(name = "core enabled={0} backend={1}")
    @CsvSource(nullValues = "-", value = {
        "-, -, false", "-, file, false", "false, postgresql, false", "false, unknown, false",
        "true, -, true", "true, postgresql, true", "true, file, false", "true, memory, false"})
    void embeddedDatasourceStaysInactiveUnlessPostgresqlIsEnabled(String enabled, String backend, boolean active)
            throws Exception {
        Map<String, String> overrides = new HashMap<>();
        if (enabled != null) {
            overrides.put("sendium.dlr.persistence.enabled", enabled);
        }
        if (backend != null) {
            overrides.put("sendium.dlr.storage.backend", backend);
        }
        assertThat(config(CORE, overrides).getValue("quarkus.datasource.dlr.active", Boolean.class))
                .isEqualTo(active);
    }

    private static SmallRyeConfig config(Path properties, Map<String, String> overrides) throws Exception {
        return new SmallRyeConfigBuilder().addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(properties.toUri().toURL(), 100))
                .withSources(new PropertiesConfigSource(overrides, "overrides", 400))
                .build();
    }
}
