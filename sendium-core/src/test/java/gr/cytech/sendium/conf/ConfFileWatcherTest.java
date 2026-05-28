package gr.cytech.sendium.conf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import static org.assertj.core.api.Assertions.assertThat;

class ConfFileWatcherTest {

    @TempDir Path tempDir;

    private SendiumConfigurationHandler configHandler;
    private ConfFileWatcher watcher;

    @BeforeEach
    void setUp() {
        configHandler = new SendiumConfigurationHandler();
        configHandler.memoryConfiguration = new ConcurrentHashMap<>();
        configHandler.defaultsConfiguration = new ConcurrentHashMap<>();
        configHandler.overriddenDefaultsConfiguration = new ConcurrentHashMap<>();
        configHandler.currentStoreConfiguration = new ConcurrentHashMap<>();
        configHandler.listeners = new CopyOnWriteArraySet<>();

        watcher = new ConfFileWatcher();
        watcher.configHandler = configHandler;
    }

    @Test
    void reloadConfigurationAddsChangesAndRemovesProperties() throws Exception {
        Path file = tempDir.resolve("smsg.properties");
        var events = new ArrayList<PropertyChangeEvent>();
        configHandler.addPropertyChangeListener(events::add);

        Files.writeString(file, "alpha=one\npassword=secret\n");
        reload(file.toFile());

        Files.writeString(file, "alpha=two\nbeta=three\n");
        reload(file.toFile());

        assertThat(configHandler.get("alpha")).isEqualTo("two");
        assertThat(configHandler.get("beta")).isEqualTo("three");
        assertThat(configHandler.get("password")).isNull();
        assertThat(events).extracting(PropertyChangeEvent::getKey)
                .containsExactlyInAnyOrder("alpha", "password", "alpha", "beta", "password");
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getKey()).isEqualTo("alpha");
            assertThat(event.getNewValue()).isEqualTo("two");
            assertThat(event.getOldValue()).isEqualTo("one");
        });
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getKey()).isEqualTo("password");
            assertThat(event.getNewValue()).isNull();
            assertThat(event.getOldValue()).isEqualTo("secret");
        });
    }

    @Test
    void reloadConfigurationSkipsMissingFile() throws Exception {
        Path file = tempDir.resolve("missing.properties");
        var events = new ArrayList<PropertyChangeEvent>();
        configHandler.addPropertyChangeListener(events::add);

        reload(file.toFile());

        assertThat(events).isEmpty();
        assertThat(configHandler.memoryConfiguration).isEmpty();
    }

    @Test
    void loadPropertiesFromFileReturnsNullForDirectory() throws Exception {
        Method method = ConfFileWatcher.class.getDeclaredMethod("loadPropertiesFromFile", File.class);
        method.setAccessible(true);

        Object result = method.invoke(watcher, tempDir.toFile());

        assertThat(result).isNull();
    }

    @Test
    void maskSecretMasksSensitiveKeysOnly() throws Exception {
        Method method = ConfFileWatcher.class.getDeclaredMethod("maskSecret", String.class, String.class);
        method.setAccessible(true);

        assertThat(method.invoke(watcher, "db.password", "secret")).isEqualTo("*****");
        assertThat(method.invoke(watcher, "api.token", "token")).isEqualTo("*****");
        assertThat(method.invoke(watcher, "http.apiKey", "api-key")).isEqualTo("*****");
        assertThat(method.invoke(watcher, "plain.key", "value")).isEqualTo("value");
        assertThat(method.invoke(watcher, "plain.key", null)).isEqualTo("null");
    }

    private void reload(File file) throws Exception {
        Method method = ConfFileWatcher.class.getDeclaredMethod("reloadConfiguration", File.class);
        method.setAccessible(true);
        method.invoke(watcher, file);
    }
}
