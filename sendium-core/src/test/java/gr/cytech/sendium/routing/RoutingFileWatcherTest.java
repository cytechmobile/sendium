package gr.cytech.sendium.routing;

import gr.cytech.sendium.conf.SendiumConfigurationHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingFileWatcherTest {

    @TempDir Path tempDir;

    private RoutingFileWatcher watcher;

    private ArrayList<Map<String, RoutingTable>> notifications;

    @BeforeEach
    void setUp() {
        watcher = new RoutingFileWatcher();
        SendiumConfigurationHandler configHandler = new SendiumConfigurationHandler();
        configHandler.memoryConfiguration = new ConcurrentHashMap<>();
        configHandler.defaultsConfiguration = new ConcurrentHashMap<>();
        configHandler.overriddenDefaultsConfiguration = new ConcurrentHashMap<>();
        configHandler.currentStoreConfiguration = new ConcurrentHashMap<>();
        configHandler.listeners = new CopyOnWriteArraySet<>();
        watcher.configHandler = configHandler;
        notifications = new ArrayList<>();
    }

    @Test
    void reloadRoutingConfigurationParsesFileAndNotifiesListeners() throws Exception {
        Path file = tempDir.resolve("routing.conf");
        Files.writeString(file, "[default]\nworker:type:==:0\n");
        watcher.addRoutingChangeListener(notifications::add);

        reload(file.toFile());

        assertThat(watcher.getUpdatedRoutes()).containsKey(RoutingFileParser.DEFAULT_ROUTING_TABLE_NAME);
        assertThat(watcher.getUpdatedRoutes().get(RoutingFileParser.DEFAULT_ROUTING_TABLE_NAME).rules).hasSize(1);
        assertThat(notifications).hasSize(1);
        assertThat(notifications.getFirst()).containsKey(RoutingFileParser.DEFAULT_ROUTING_TABLE_NAME);
    }

    @Test
    void reloadRoutingConfigurationContinuesWhenListenerThrows() throws Exception {
        Path file = tempDir.resolve("routing.conf");
        Files.writeString(file, "[default]\nworker:type:==:0\n");
        watcher.addRoutingChangeListener(table -> {
            throw new RuntimeException("boom");
        });
        watcher.addRoutingChangeListener(notifications::add);

        reload(file.toFile());

        assertThat(notifications).hasSize(1);
    }

    @Test
    void reloadRoutingConfigurationRetainsPreviousRoutesWhenReloadFails() throws Exception {
        Path file = tempDir.resolve("routing.conf");
        Files.writeString(file, "[default]\nworker:type:==:0\n");
        reload(file.toFile());
        Map<String, RoutingTable> previous = watcher.getUpdatedRoutes();

        reload(tempDir.toFile());

        assertThat(watcher.getUpdatedRoutes()).isEqualTo(previous);
    }

    @Test
    void getUpdatedRoutesReturnsEmptyBeforeFirstLoadAndUnmodifiableAfterLoad() throws Exception {
        assertThat(watcher.getUpdatedRoutes()).isEmpty();
        Path file = tempDir.resolve("routing.conf");
        Files.writeString(file, "[default]\nworker:type:==:0\n");
        reload(file.toFile());

        assertThatThrownBy(() -> watcher.getUpdatedRoutes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void removeRoutingChangeListenerStopsNotifications() throws Exception {
        Path file = tempDir.resolve("routing.conf");
        Files.writeString(file, "[default]\nworker:type:==:0\n");
        RoutingChangeListener listener = notifications::add;
        watcher.addRoutingChangeListener(listener);
        watcher.removeRoutingChangeListener(listener);

        reload(file.toFile());

        assertThat(notifications).isEmpty();
    }

    private void reload(File file) throws Exception {
        Method method = RoutingFileWatcher.class.getDeclaredMethod("reloadRoutingConfiguration", File.class);
        method.setAccessible(true);
        method.invoke(watcher, file);
    }
}
