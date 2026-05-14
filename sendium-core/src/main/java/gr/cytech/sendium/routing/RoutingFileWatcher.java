package gr.cytech.sendium.routing;

import gr.cytech.sendium.conf.SendiumConfigurationHandler;
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
public class RoutingFileWatcher {
    private static final Logger logger = LoggerFactory.getLogger(RoutingFileWatcher.class);
    private static final String ROUTING_PATH_KEY = "smsg.routing.file.path";
    private static final long DEBOUNCE_DELAY_MS = 200;
    private static final long RESTART_DELAY_MS = 5000;
    protected Map<String, RoutingTable> updatedRoutes;
    @Inject SendiumConfigurationHandler configHandler;
    private Thread watcherThread;
    private volatile boolean running = false;

    private final Set<RoutingChangeListener> listeners = new CopyOnWriteArraySet<>();

    void onStart(@Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) StartupEvent ev) {
        String pathStr = configHandler.get(ROUTING_PATH_KEY);
        if (pathStr == null) {
            logger.warn("Routing watcher disabled: No routing path defined for '{}'", ROUTING_PATH_KEY);
            return;
        }
        running = true;

        File file = new File(pathStr);
        ensureDirectoryExists(file);

        // Perform initial load immediately on startup
        reloadRoutingConfiguration(file);

        this.watcherThread = Thread.ofVirtual().name("sendium-routing-watcher").start(() -> runWatcher(file));
        logger.info("Started Virtual Thread routing watcher for: {}", file);
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
                            logger.info("Routing Watcher thread interrupted, stopping.");
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
                            logger.info("Routing configuration file changed. Reloading...");
                            reloadRoutingConfiguration(targetFile);
                        }

                        if (!key.reset()) {
                            logger.warn("WatchKey invalid for routing. Restarting watcher service...");
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                logger.error("Fatal error in Routing File Watcher. Restarting in {} ms...", RESTART_DELAY_MS, e);
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

    private synchronized void reloadRoutingConfiguration(File file) {
        if (!file.exists()) {
            logger.warn("Routing file {} does not exist yet.", file.getAbsolutePath());
            return;
        }

        try {
            updatedRoutes = RoutingFileParser.loadAndParse(file.toPath());
            logger.info("Routing configuration reloaded successfully. Total tables: {}", updatedRoutes.size());
            notifyListeners(updatedRoutes);
        } catch (Exception e) {
            logger.error("Failed to parse routing file. Retaining old routing state.", e);
        }
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized Map<String, RoutingTable> getUpdatedRoutes() {
        if (updatedRoutes == null || updatedRoutes.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(updatedRoutes);
    }

    public void addRoutingChangeListener(RoutingChangeListener listener) {
        listeners.add(listener);
    }

    public void removeRoutingChangeListener(RoutingChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(Map<String, RoutingTable> table) {
        for (RoutingChangeListener listener : listeners) {
            try {
                listener.routingChange(table);
            } catch (Exception e) {
                logger.error("Error notifying routing change listener", e);
            }
        }
    }
}