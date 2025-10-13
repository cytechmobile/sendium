package smsgateway.smpp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.impl.ConcurrentHashSet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import smsgateway.providers.LogProvider;
import smsgateway.routing.destination.AbstractOutWorker;
import smsgateway.services.DlrForwardingService;
import smsgateway.services.DlrMappingService;

@ApplicationScoped
public class DynamicVendorConfigWatcher {
    private static final Logger logger =
            LogProvider.getSmppClientLogger(DynamicVendorConfigWatcher.class.getName());
    @Inject DlrForwardingService dlrForwardingService;

    @ConfigProperty(
            name = "sms.gateway.smpp.dynamic.config.file.path",
            defaultValue = "conf/vendors-config.json")
    String configFilePath;

    @Inject SmppClientHolder smppClientHolder;
    @Inject DlrMappingService dlrMappingService;
    @Inject ObjectMapper objectMapper;

    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Future<?>> activeThreadWorkers = new ConcurrentHashMap<>();
    private final Map<String, AbstractOutWorker> workersActive = new ConcurrentHashMap<>();
    private Set<VendorConf> currentVendorsConfs = new ConcurrentHashSet<>();

    void onStart(@Observes StartupEvent ev) {
        forceReloadConfig();
    }

    void onStop(@Observes ShutdownEvent ev) {
        shutdown();
    }

    void shutdown() {
        logger.info("Shutting down DynamicVendorConfigWatcher and stopping all workers...");
        for (var w : workersActive.entrySet()) {
            try {
                w.getValue().stopWorker();
            } catch (Exception e) {
                logger.warn("error stopping worker: {}", w.getKey());
            }
        }
        executorService.shutdownNow(); // Interrupts all running tasks
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn("Executor service did not terminate in 30 seconds.");
            } else {
                logger.info("All workers have been stopped.");
            }
        } catch (InterruptedException e) {
            logger.warn("Shutdown was interrupted while waiting for workers to terminate.");
        }
        logger.info("DynamicVendorConfigWatcher has been shut down.");
    }

    private void stopWorker(String vendorId) {
        logger.info("Stopping worker for vendor ID: {}.", vendorId);
        Future<?> future = activeThreadWorkers.remove(vendorId);
        if (future != null) {
            future.cancel(true);
        }
        var worker = workersActive.get(vendorId);
        if (worker != null) {
            worker.stopWorker();
        }
        workersActive.remove(vendorId);
    }

    private Path resolveConfigFilePath() throws ConfigFileResolutionException {
        try {
            // Try to resolve as an absolute path first
            var path = Paths.get(configFilePath);
            if (!Files.exists(path)) {
                logger.info(
                        "Vendor config file not found at {}. Creating with default content.",
                        configFilePath);
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                String defaultVendorsConfigJson = "[]";
                Files.writeString(path, defaultVendorsConfigJson, StandardCharsets.UTF_8);
                logger.info("Created default vendor config file at: {}", configFilePath);
            }
            return path;
        } catch (Exception e) {
            throw new ConfigFileResolutionException(
                    "Failed to resolve or create default vendor configuration file path: "
                            + configFilePath,
                    e);
        }
    }

    public void addVendor(VendorConf vendor) {
        var existingVendor =
                currentVendorsConfs.stream()
                        .filter(v -> v.getId().equals(vendor.getId()))
                        .findFirst();
        currentVendorsConfs.add(vendor);
        if (vendor.isEnabled()) {
            startOrRestartWorker(vendor, existingVendor.orElse(null));
        }
    }

    public synchronized void removeVendor(VendorConf vendorConf) {
        currentVendorsConfs.removeIf(version -> version.getId().equals(vendorConf.getId()));
        stopWorker(vendorConf.getId());
    }

    private void startOrRestartWorker(VendorConf vendor, VendorConf existingConfig) {
        if (existingConfig == null) { // New worker
            logger.info("Starting new worker for vendor ID: {}", vendor.getId());
            AbstractOutWorker newWorker;
            if (SmppClientWorker.TYPE.equalsIgnoreCase(vendor.getType())) {
                newWorker =
                        new SmppClientWorker(
                                vendor, smppClientHolder, dlrForwardingService, dlrMappingService);
                Future<?> future = executorService.submit((Runnable) newWorker);
                activeThreadWorkers.put(vendor.getId(), future);
                workersActive.put(vendor.getId(), newWorker);
                logger.info(
                        "Worker for {} RESTARTED. Active workers map size: {}",
                        vendor.getId(),
                        workersActive.size());
            } else {
                logger.info("HTTP Worker for {} not supported yet", vendor.getId());
            }
        } else if (!vendor.equals(existingConfig)) { // Configuration changed, restart worker
            logger.info(
                    "Configuration changed for vendor ID: {}. Restarting worker.", vendor.getId());
            stopWorker(vendor.getId()); // Stop the old one first
            AbstractOutWorker newWorker;
            if (SmppClientWorker.TYPE.equalsIgnoreCase(vendor.getType())) {
                newWorker =
                        new SmppClientWorker(
                                vendor, smppClientHolder, dlrForwardingService, dlrMappingService);
                Future<?> future = executorService.submit((Runnable) newWorker);
                activeThreadWorkers.put(vendor.getId(), future);
                workersActive.put(vendor.getId(), newWorker);
                logger.info(
                        "Worker for {} RESTARTED. Active workers map size: {}",
                        vendor.getId(),
                        workersActive.size());
            } else {
                logger.info("HTTP Worker for {} not supported yet", vendor.getId());
            }
        } else {
            logger.debug("No changes for already running worker ID: {}", vendor.getId());
        }
    }

    private void synchronizeWorkers() {
        logger.debug("Synchronizing SMPP workers based on current configuration.");
        Set<String> configuredEnabledVendorIds =
                currentVendorsConfs.stream()
                        .filter(VendorConf::isEnabled)
                        .map(VendorConf::getId)
                        .collect(Collectors.toSet());

        // Stop workers that are no longer configured or are disabled
        for (String runningVendorId : new ArrayList<>(workersActive.keySet())) {
            if (!configuredEnabledVendorIds.contains(runningVendorId)) {
                stopWorker(runningVendorId);
            }
        }

        // Start new workers or restart workers with changed configurations
        for (VendorConf vendor : currentVendorsConfs) {
            if (vendor.isEnabled()) {
                var work = workersActive.get(vendor.getId());
                VendorConf existingConfig = null;
                if (work != null) {
                    existingConfig = work.getVendorConf();
                }
                // If it's not in workersActive, existingConfig will be null, leading to a
                // start.
                // If it is, startOrRestartWorker will check if it needs restart.
                startOrRestartWorker(vendor, existingConfig);
            } else {
                // If a worker for a now-disabled vendor is somehow still in workersActive
                // (e.g., if it wasn't running but config existed)
                // ensure it's fully stopped if it was active. The loop above already handles
                // running workers.
                // This specific case (disabled but in workersActive and not in
                // activeThreadWorkers) is less common.
                // The primary stop logic is handled by iterating activeThreadWorkers.keySet().
                // However, if it's disabled and was running, the first loop (iterating
                // activeThreadWorkers) would have stopped it.
                // If it's disabled and was NOT running, startOrRestartWorker won't be called.
                // If it's disabled and its config is still in workersActive, we might want to
                // remove it from workersActive.
                if (workersActive.containsKey(vendor.getId())) {
                    logger.debug(
                            "Vendor {} is disabled. Ensuring it is not marked as active if it was previously.",
                            vendor.getId());
                    // If it was running, the first loop (based on activeThreadWorkers) would have
                    // stopped it.
                    // If it wasn't running but we have a stale config, just remove from
                    if (!activeThreadWorkers.containsKey(vendor.getId())) {
                        workersActive.remove(vendor.getId());
                    }
                }
            }
        }
        logger.debug(
                "Worker synchronization complete. Active smpp workers: {}. Active configs: {}",
                activeThreadWorkers.size(),
                workersActive.size());
    }

    public Map<String, AbstractOutWorker> getWorkersActive() {
        return workersActive;
    }

    public VendorConf getActiveVendorConfig(String vendorId) {
        return workersActive.get(vendorId).getVendorConf();
    }

    public AbstractOutWorker getWorker(String vendorId) {
        return workersActive.get(vendorId);
    }

    public Set<VendorConf> getCurrentVendorsConfs() {
        return currentVendorsConfs;
    }

    // --- Methods for Testability ---

    public void clearInternalWorkerStatesForTest() {
        logger.info("Clearing internal worker states for testing purposes.");

        // Cancel all active futures
        for (Future<?> future : activeThreadWorkers.values()) {
            if (future != null && !future.isDone() && !future.isCancelled()) {
                future.cancel(true); // Interrupt the worker thread
            }
        }
        activeThreadWorkers.clear();
        workersActive.clear();
        logger.info(
                "Internal worker states cleared for testing. Active workers: {}",
                activeThreadWorkers.size());
    }

    public void resetConfigStateForTest(String configFilePathForTest) {
        logger.info(
                "Resetting config state for testing purposes. Using path: {}",
                configFilePathForTest);
        this.configFilePath =
                configFilePathForTest; // Update path for subsequent checkConfiguration calls
        this.currentVendorsConfs = new HashSet<>();
        clearInternalWorkerStatesForTest();
        logger.info("Config state (in-memory) reset for testing.");
    }

    // Custom exception for reload failures
    public static class ConfigReloadException extends RuntimeException {
        public ConfigReloadException(String message) {
            super(message);
        }

        public ConfigReloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public void persistCurrentVendorsConf() throws Exception {
        logger.info("Persisting vendor configuration to: {}", configFilePath);
        try {
            var path = resolveConfigFilePath();
            objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(path.toFile(), currentVendorsConfs);
            forceReloadConfig();
        } catch (Exception e) {
            logger.warn("error persisting SMPP vendor configuration to: {}", configFilePath, e);
        }
    }

    public boolean persist(Set<VendorConf> vendors) {
        if (vendors == null) {
            vendors = new HashSet<>();
        }
        logger.info("Persisting SMPP vendor configuration to: {}", configFilePath);
        try {
            var path = resolveConfigFilePath();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), vendors);
            forceReloadConfig();
            return true;
        } catch (Exception e) {
            logger.warn("error persisting SMPP vendor configuration to: {}", configFilePath, e);
            return false;
        }
    }

    public void forceReloadConfig() throws ConfigReloadException {
        logger.info("Force reloading SMPP vendor configuration from: {}", configFilePath);
        Path path;
        try {
            path = resolveConfigFilePath();
        } catch (ConfigFileResolutionException e) {
            logger.warn(
                    "Failed to resolve configuration file path during force reload: {}",
                    e.getMessage());
            throw new ConfigReloadException(e.getMessage(), e);
        }
        try {
            logger.info("Attempting to read and parse configuration file: {}", path);
            var json = Files.readString(path, StandardCharsets.UTF_8);
            Set<VendorConf> newVendorConfigs =
                    objectMapper.readValue(json, new TypeReference<>() {});
            if (newVendorConfigs != null) {
                currentVendorsConfs = newVendorConfigs; // Update current vendors
                logger.info(
                        "Successfully force-reloaded SMPP vendor configurations. Found {} vendors. Applying changes.",
                        currentVendorsConfs.size());
                synchronizeWorkers(); // Apply changes by synchronizing workers
            } else {
                logger.warn(
                        "Parsed vendor configurations resulted in null during force reload. Configuration will not be applied.");
                throw new ConfigReloadException(
                        "Parsed vendor configurations resulted in null from file: " + path);
            }
        } catch (Exception e) {
            logger.error("Error reading configuration file during force reload: {}", path, e);
            throw new ConfigReloadException("Error reading configuration file: " + path, e);
        }
    }

    public String getConfigFilePath() {
        return configFilePath;
    }

    private static class ConfigFileResolutionException extends Exception {
        public ConfigFileResolutionException(String message) {
            super(message);
        }

        public ConfigFileResolutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
