package gr.cytech.sendium.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.base.Strings;
import gr.cytech.sendium.conf.SendiumConfigurationHandler;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

@ApplicationScoped
public class CredentialFileWatcher {
    private static final Logger logger = LoggerFactory.getLogger(CredentialFileWatcher.class);
    private static final String CREDENTIAL_PATH_KEY = "smsg.credentials.file.path";
    private static final long DEBOUNCE_DELAY_MS = 200;
    private static final long RESTART_DELAY_MS = 5000;

    protected Map<String, Credential> validCredentials;

    @Inject
    SendiumConfigurationHandler configHandler;

    private Thread watcherThread;
    private volatile boolean running = false;
    private final Set<CredentialChangeListener> listeners = new CopyOnWriteArraySet<>();

    void onStart(@Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) StartupEvent ev) {
        String pathStr = configHandler.get(CREDENTIAL_PATH_KEY);
        if (pathStr == null) {
            logger.warn("Credential watcher disabled: No path defined for '{}'", CREDENTIAL_PATH_KEY);
            return;
        }
        running = true;

        File file = new File(pathStr);
        ensureDirectoryExists(file);

        reloadCredentialConfiguration(file);

        this.watcherThread = Thread.ofVirtual().name("sendium-credentials-watcher").start(() -> runWatcher(file));
        logger.info("Started Virtual Thread credentials watcher for: {}", file);
    }

    void onStop(@Observes ShutdownEvent ev) {
        this.running = false;
        if (watcherThread != null) {
            watcherThread.interrupt();
        }
    }

    private void runWatcher(File targetFile) {
        Path directory = targetFile.getParentFile().toPath();
        String targetFilename = targetFile.getName();

        while (running) {
            try {
                if (!Files.exists(directory)) {
                    Files.createDirectories(directory);
                }

                try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
                    directory.register(watcher, ENTRY_MODIFY, ENTRY_CREATE);
                    long lastReloadTime = 0;

                    while (running) {
                        WatchKey key;
                        try {
                            key = watcher.take();
                        } catch (InterruptedException e) {
                            logger.info("Credential Watcher thread interrupted, stopping.");
                            return;
                        }

                        boolean shouldReload = false;
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                                continue;
                            }

                            Path changedPath = (Path) event.context();
                            if (changedPath != null && changedPath.toString().equals(targetFilename)) {
                                long now = System.currentTimeMillis();
                                if ((now - lastReloadTime) > DEBOUNCE_DELAY_MS) {
                                    shouldReload = true;
                                    lastReloadTime = now;
                                }
                            }
                        }

                        if (shouldReload) {
                            logger.info("Credential configuration file changed. Reloading...");
                            reloadCredentialConfiguration(targetFile);
                        }

                        if (!key.reset()) {
                            logger.warn("WatchKey invalid for credentials. Restarting watcher service...");
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                logger.error("Fatal error in Credential File Watcher. Restarting in {} ms...", RESTART_DELAY_MS, e);
                try {
                    Thread.sleep(RESTART_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void ensureDirectoryExists(File file) {
        if (!file.exists() && file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
    }

    private synchronized void reloadCredentialConfiguration(File file) {
        if (!file.exists()) {
            logger.warn("Credential file {} does not exist yet.", file.getAbsolutePath());
            return;
        }

        try {
            validCredentials = CredentialFileParser.loadAndParse(file.toPath());
            logger.info("Credential configuration reloaded successfully. Total credentials loaded: {}", validCredentials.size());
            notifyListeners(validCredentials);
        } catch (Exception e) {
            logger.error("Failed to parse credential file. Retaining old credential state.", e);
        }
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized Map<String, Credential> getValidCredentials() {
        if (validCredentials == null || validCredentials.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(validCredentials);
    }

    public void addCredentialChangeListener(CredentialChangeListener listener) {
        listeners.add(listener);
    }

    public void removeCredentialChangeListener(CredentialChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(Map<String, Credential> credentialsMap) {
        for (CredentialChangeListener listener : listeners) {
            try {
                listener.credentialsChanged(credentialsMap);
            } catch (Exception e) {
                logger.error("Error notifying credential change listener", e);
            }
        }
    }

    public enum CredentialType {
        SMPP, HTTP
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Credential(
            CredentialType type,
            String accountId,
            String product,
            String systemId,
            String password,
            String apiKey,
            Set<String> allowedIps
    ) {
        // Basic validation to ensure the required fields exist based on type
        public boolean isValid() {
            if (type == CredentialType.SMPP) {
                return systemId != null && password != null;
            } else if (type == CredentialType.HTTP) {
                return apiKey != null || (systemId != null && password != null);
            }
            return false;
        }

        public String getLookupKey() {
            if (type == CredentialType.SMPP) {
                return systemId;
            } else {
                if (!Strings.isNullOrEmpty(apiKey)) {
                    return apiKey;
                } else {
                    return systemId;
                }
            }
        }

        /**
         * Validates if the given IP address is authorized to use these credentials.
         * * @param ip The IP address of the incoming connection
         * @return true if allowed, false otherwise
         */
        public boolean isIpAllowed(String ip) {
            // NOTE: If allowedIps is null or empty, this implementation defaults to "allow all".
            // If your security policy requires strict whitelisting, change this to return false.
            if (allowedIps == null || allowedIps.isEmpty()) {
                return true;
            }
            return allowedIps.contains(ip);
        }
    }
}