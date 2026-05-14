package gr.cytech.sendium.conf;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

@ApplicationScoped
public class ConfFileWatcher {

    private static final Logger logger = LoggerFactory.getLogger(ConfFileWatcher.class);
    private static final String CONFIG_PATH_KEY = "smsg.properties.file.path";

    // Debounce time: ignore events happening closer than this
    private static final long DEBOUNCE_DELAY_MS = 200;
    private static final long RESTART_DELAY_MS = 5000;
    @Inject SendiumConfigurationHandler configHandler;

    private volatile boolean running = true;
    private Map<String, String> lastKnownFileState = Collections.emptyMap();
    private Thread watcherThread;

    void onStart(@Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) StartupEvent ev) {
        String pathStr = configHandler.get(CONFIG_PATH_KEY);
        if (pathStr == null) {
            logger.warn("Watcher disabled: No config path defined for '{}'", CONFIG_PATH_KEY);
            return;
        }

        File file = new File(pathStr);
        ensureDirectoryExists(file);

        // Load and push initial state to config handler
        logger.info("Loading initial configuration from: {}", file);
        reloadConfiguration(file);

        this.watcherThread = Thread.ofVirtual().name("smsg-config-watcher").start(() -> runWatcher(file));

        logger.info("Started Virtual Thread watcher for: {}", file);
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
                        WatchKey key = null;
                        try {
                            key = watcher.take();
                        } catch (InterruptedException e) {
                            logger.info("Watcher thread interrupted, stopping.");
                        }

                        if (key == null) {
                            continue;
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
                            logger.info("Configuration file changed. Reloading...");
                            reloadConfiguration(targetFile);
                        }

                        if (!key.reset()) {
                            logger.warn("WatchKey invalid (Directory deleted?). Restarting watcher service...");
                            break; // Break INNER loop to trigger restart in OUTER loop
                        }
                    }
                }
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                logger.error("Fatal error in Config File Watcher. Restarting in {} ms...", RESTART_DELAY_MS, e);
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

    private synchronized void reloadConfiguration(File file) {
        if (!file.exists()) {
            return;
        }

        Map<String, String> newFileProps = loadPropertiesFromFile(file);
        if (newFileProps == null) {
            return;
        }

        boolean hasChanges = false;

        // --- 1. Detect Updates & Additions ---
        for (Map.Entry<String, String> entry : newFileProps.entrySet()) {
            String key = entry.getKey();
            String newVal = entry.getValue();
            String oldVal = lastKnownFileState.get(key);

            if (oldVal == null) {
                // CASE: NEW KEY
                configHandler.set(key, newVal);
                configHandler.firePropertyChangeEvent(key, newVal, null);
                logger.info("[CONFIG ADDED] {} = {}", key, maskSecret(key, newVal));
                hasChanges = true;
            } else if (!newVal.equals(oldVal)) {
                // CASE: VALUE CHANGED
                configHandler.set(key, newVal);
                configHandler.firePropertyChangeEvent(key, newVal, oldVal);
                logger.info(
                        "[CONFIG CHANGED] {} : '{}' -> '{}'",
                        key,
                        maskSecret(key, oldVal),
                        maskSecret(key, newVal));
                hasChanges = true;
            }
        }

        for (Map.Entry<String, String> entry : lastKnownFileState.entrySet()) {
            String key = entry.getKey();
            if (!newFileProps.containsKey(key)) {
                // CASE: REMOVED KEY
                configHandler.remove(key);
                configHandler.firePropertyChangeEvent(key, null,  entry.getValue());
                logger.info(
                        "[CONFIG REMOVED] {} (was '{}')", key, maskSecret(key, entry.getValue()));
                hasChanges = true;
            }
        }

        if (hasChanges) {
            logger.info("Configuration reload completed successfully.");
        }

        // Update state to reflect current file content
        this.lastKnownFileState = newFileProps;
    }

    /** Simple helper to prevent logging cleartext passwords in production logs. */
    private String maskSecret(String key, String value) {
        if (value == null) {
            return "null";
        }
        String lowerKey = key.toLowerCase();
        if (lowerKey.contains("password") ||
                lowerKey.contains("secret") ||
                lowerKey.contains("token")) {
            return "*****";
        }
        return value;
    }

    private Map<String, String> loadPropertiesFromFile(File file) {
        if (!file.exists()) {
            return Collections.emptyMap();
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            Properties p = new Properties();
            p.load(fis);
            Map<String, String> map = new HashMap<>();
            for (String name : p.stringPropertyNames()) {
                map.put(name, p.getProperty(name));
            }
            return map;
        } catch (IOException e) {
            logger.error("Error reading config", e);
            return null;
        }
    }
}