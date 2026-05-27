package gr.cytech.sendium.auth;

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

class CredentialFileWatcherTest {

    @TempDir Path tempDir;

    private CredentialFileWatcher watcher;
    private ArrayList<Map<String, CredentialFileWatcher.Credential>> notifications;

    @BeforeEach
    void setUp() {
        watcher = new CredentialFileWatcher();
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
    void reloadCredentialConfigurationParsesFileAndNotifiesListeners() throws Exception {
        Path file = tempDir.resolve("credentials.yml");
        Files.writeString(file, """
                credentials:
                  - type: HTTP
                    systemId: http-user
                    password: secret
                """);
        watcher.addCredentialChangeListener(notifications::add);

        reload(file.toFile());

        assertThat(watcher.getValidCredentials()).containsKey("http-user");
        assertThat(notifications).hasSize(1);
        assertThat(notifications.getFirst()).containsKey("http-user");
    }

    @Test
    void reloadCredentialConfigurationContinuesWhenListenerThrows() throws Exception {
        Path file = tempDir.resolve("credentials.yml");
        Files.writeString(file, """
                credentials:
                  - type: HTTP
                    apiKey: api-key
                """);
        watcher.addCredentialChangeListener(credentials -> {
            throw new RuntimeException("boom");
        });
        watcher.addCredentialChangeListener(notifications::add);

        reload(file.toFile());

        assertThat(notifications).hasSize(1);
        assertThat(notifications.getFirst()).containsKey("api-key");
    }

    @Test
    void reloadCredentialConfigurationRetainsPreviousCredentialsWhenReloadFails() throws Exception {
        Path file = tempDir.resolve("credentials.yml");
        Files.writeString(file, """
                credentials:
                  - type: SMPP
                    systemId: smpp-user
                    password: secret
                """);
        reload(file.toFile());

        reload(tempDir.toFile());

        assertThat(watcher.getValidCredentials()).containsKey("smpp-user");
    }

    @Test
    void getValidCredentialsReturnsEmptyBeforeLoadAndUnmodifiableAfterLoad() throws Exception {
        assertThat(watcher.getValidCredentials()).isEmpty();
        Path file = tempDir.resolve("credentials.yml");
        Files.writeString(file, """
                credentials:
                  - type: HTTP
                    apiKey: api-key
                """);
        reload(file.toFile());

        assertThatThrownBy(() -> watcher.getValidCredentials().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void removeCredentialChangeListenerStopsNotifications() throws Exception {
        Path file = tempDir.resolve("credentials.yml");
        Files.writeString(file, """
                credentials:
                  - type: HTTP
                    apiKey: api-key
                """);
        CredentialChangeListener listener = notifications::add;
        watcher.addCredentialChangeListener(listener);
        watcher.removeCredentialChangeListener(listener);

        reload(file.toFile());

        assertThat(notifications).isEmpty();
    }

    @Test
    void credentialValidationAndLookupKeyFollowCredentialType() {
        var smpp = new CredentialFileWatcher.Credential(
                CredentialFileWatcher.CredentialType.SMPP, "account", null, "system", "pass", null, null);
        var httpApiKey = new CredentialFileWatcher.Credential(
                CredentialFileWatcher.CredentialType.HTTP, "account", null, null, null, "api", null);
        var httpUserPass = new CredentialFileWatcher.Credential(
                CredentialFileWatcher.CredentialType.HTTP, "account", null, "user", "pass", null, null);
        var invalid = new CredentialFileWatcher.Credential(
                CredentialFileWatcher.CredentialType.HTTP, "account", null, null, null, null, null);

        assertThat(smpp.isValid()).isTrue();
        assertThat(smpp.getLookupKey()).isEqualTo("system");
        assertThat(httpApiKey.isValid()).isTrue();
        assertThat(httpApiKey.getLookupKey()).isEqualTo("api");
        assertThat(httpUserPass.isValid()).isTrue();
        assertThat(httpUserPass.getLookupKey()).isEqualTo("user");
        assertThat(invalid.isValid()).isFalse();
    }

    private void reload(File file) throws Exception {
        Method method = CredentialFileWatcher.class.getDeclaredMethod("reloadCredentialConfiguration", File.class);
        method.setAccessible(true);
        method.invoke(watcher, file);
    }
}
