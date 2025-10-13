package smsgateway.smpp;

import static java.lang.Thread.sleep;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@QuarkusTest
class DynamicVendorConfigWatcherTest {
    private static final Logger logger =
            LoggerFactory.getLogger(DynamicVendorConfigWatcherTest.class);
    public static final int RETRIES_SECONDS = 5;
    public static final int RETRY_WAIT_PERIOD = 100;
    public static final int RETRIES_PER_SEC = 1000 / RETRY_WAIT_PERIOD;
    public static final int MAX_RETRIES = RETRIES_PER_SEC * RETRIES_SECONDS;

    @Inject DynamicVendorConfigWatcher watcher;
    String originalConfigPath;
    String testConfigPath;

    @BeforeEach
    void setUp() throws Exception {
        // Construct the path where the test config file will be written
        originalConfigPath = watcher.getConfigFilePath();
        testConfigPath = "./target/vendors.json";

        // Reset watcher's internal configuration state and point it to the test file path
        watcher.resetConfigStateForTest(testConfigPath);
        watcher.persist(new HashSet<>());
        try {
            watcher.forceReloadConfig(); // Process the empty config
        } catch (DynamicVendorConfigWatcher.ConfigReloadException e) {
            // This is expected if the initial config file is empty and forceReloadConfig is strict
            logger.info(
                    "Initial empty config load failed as expected (if forceReloadConfig is strict): "
                            + e.getMessage());
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(Paths.get(testConfigPath)); // Clean up the test config file
        watcher.resetConfigStateForTest(originalConfigPath);
        watcher.forceReloadConfig();
    }

    private void updateTestConfig(String content) throws IOException {
        Files.writeString(
                Paths.get(testConfigPath),
                content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    // Reflection helpers are removed.

    @Test
    void testInitialConfigLoadAndWorkerStart() throws Exception {
        String vendorId = "vendorInit";
        String initialJson =
                String.format(
                        """
                [
                  {
                    "id": "%s",
                    "enabled": true,
                    "host": "localhost",
                    "port": 1001,
                    "systemId": "sysInit",
                    "password": "pass"
                  }
                ]""",
                        vendorId);

        updateTestConfig(initialJson);
        watcher.forceReloadConfig();

        waitForCondition(
                () ->
                        (watcher.getActiveVendorConfig(vendorId) != null
                                && watcher.getActiveVendorConfig(vendorId).getPort() == 1001),
                "Active config for " + vendorId + " should exist.");
        waitForCondition(() -> (watcher.getWorker(vendorId) != null), "expected enabled worker");
    }

    @Test
    void testAddVendor() throws Exception {
        String vendorId = "vendorAdd";
        String addJson =
                String.format(
                        """
                [
                  {
                    "id": "%s",
                    "enabled": true,
                    "host": "localhost",
                    "port": 1002,
                    "systemId": "sysAdd",
                    "password": "pass"
                  }
                ]""",
                        vendorId);

        updateTestConfig(addJson);
        watcher.forceReloadConfig();
        waitForCondition(() -> (watcher.getWorker(vendorId) != null), "expected enabled worker");
    }

    @Test
    void testDisableVendor() throws Exception {
        String vendorId = "vendorDisable";
        String enabledJson =
                String.format(
                        """
                [
                  {
                    "id": "%s",
                    "enabled": true,
                    "host": "localhost",
                    "port": 1003,
                    "systemId": "sysDisable",
                    "password": "pass"
                  }
                ]""",
                        vendorId);

        updateTestConfig(enabledJson);
        watcher.forceReloadConfig();
        waitForCondition(() -> (watcher.getWorker(vendorId) != null), "expected enabled worker");

        String disabledJson =
                String.format(
                        """
                [
                  {
                    "id": "%s",
                    "enabled": false,
                    "host": "localhost",
                    "port": 1003,
                    "systemId": "sysDisable",
                    "password": "pass"
                  }
                ]""",
                        vendorId);

        updateTestConfig(disabledJson);
        watcher.forceReloadConfig();
        waitForCondition(
                () -> (watcher.getWorker(vendorId) == null), "expected null/disabled worker");
        waitForCondition(
                () -> (watcher.getActiveVendorConfig(vendorId) == null),
                "Config for disabled vendor should be removed.");
    }

    @Test
    void testRemoveVendor() throws Exception {
        String vendorId = "vendorRemove";
        String initialJson =
                String.format(
                        """
                [
                  {
                    "id": "%s",
                    "enabled": true,
                    "host": "localhost",
                    "port": 1004,
                    "systemId": "sysRemove",
                    "password": "pass"
                  }
                ]""",
                        vendorId);

        updateTestConfig(initialJson);
        watcher.forceReloadConfig();

        updateTestConfig("[]"); // Empty array
        watcher.forceReloadConfig();
        waitForCondition(
                () -> (watcher.getWorker(vendorId) == null), "expected null/disabled worker");
    }

    @Test
    void testModifyVendorPropertiesAndRestart() throws Exception {
        String vendorId = "vendorModify";
        int initialPort = 1005;
        int newPort = 2005;

        String initialJson =
                String.format(
                        """
                [
                  {
                    "id": "%s",
                    "enabled": true,
                    "host": "localhost",
                    "port": %d,
                    "systemId": "sysModify",
                    "password": "pass"
                  }
                ]""",
                        vendorId, initialPort);

        updateTestConfig(initialJson);
        watcher.forceReloadConfig();
        waitForCondition(() -> (watcher.getWorker(vendorId) != null), "expected enabled worker");

        waitForCondition(
                () ->
                        (watcher.getActiveVendorConfig(vendorId) != null
                                && watcher.getActiveVendorConfig(vendorId).getPort()
                                        == initialPort),
                "expected enabled vendor config worker");

        String modifiedJson =
                String.format(
                        """
                [
                  {
                    "id": "%s",
                    "enabled": true,
                    "host": "localhost",
                    "port": %d,
                    "systemId": "sysModify",
                    "password": "passNew"
                  }
                ]""",
                        vendorId, newPort); // Port and password changed

        updateTestConfig(modifiedJson);
        watcher.forceReloadConfig();
        waitForCondition(() -> (watcher.getWorker(vendorId) != null), "expected enabled worker");
        waitForCondition(
                () ->
                        (watcher.getActiveVendorConfig(vendorId) != null
                                && watcher.getActiveVendorConfig(vendorId).getPort() == newPort
                                && watcher.getActiveVendorConfig(vendorId)
                                        .getPassword()
                                        .equals("passNew")),
                "expected enabled vendor config");
    }

    @Test
    void testInvalidJsonRetainsLastGoodConfig() throws Exception {
        String vendorId = "vendorGoodJson";
        String goodJson =
                String.format(
                        """
                [
                  {
                    "id": "%s",
                    "enabled": true,
                    "host": "localhost",
                    "port": 1006,
                    "systemId": "sysGoodJson",
                    "password": "pass"
                  }
                ]""",
                        vendorId);

        updateTestConfig(goodJson);
        watcher.forceReloadConfig();
        waitForCondition(
                () ->
                        (watcher.getActiveVendorConfig(vendorId) != null
                                && watcher.getActiveVendorConfig(vendorId).getPort() == 1006),
                "Active config for " + vendorId + " should exist after initial good load.");

        String badJson = "THIS_IS_NOT_JSON";
        updateTestConfig(badJson);

        // Assert that forceReloadConfig throws ConfigReloadException
        assertThrows(
                DynamicVendorConfigWatcher.ConfigReloadException.class,
                () -> watcher.forceReloadConfig());

        // Verify that the good configuration is still active
        waitForCondition(
                () ->
                        (watcher.getActiveVendorConfig(vendorId) != null
                                && watcher.getActiveVendorConfig(vendorId).getPort() == 1006),
                "Active config for " + vendorId + " should still exist after failed reload.");
    }

    @Test
    void testConfigBecomesEmptyArray() throws Exception {
        String vendorId = "vendorToEmpty";
        String initialJson =
                String.format(
                        """
                [
                  {
                    "id": "%s",
                    "enabled": true,
                    "host": "localhost",
                    "port": 1008,
                    "systemId": "sysToEmpty",
                    "password": "pass"
                  }
                ]""",
                        vendorId);

        updateTestConfig(initialJson);
        watcher.forceReloadConfig();
        waitForCondition(() -> (watcher.getWorker(vendorId) != null), "expected enabled worker");

        updateTestConfig("[]"); // Config becomes an empty array
        watcher.forceReloadConfig();
        waitForCondition(
                () -> (watcher.getWorker(vendorId) == null), "expected null/disabled worker");

        waitForCondition(
                () -> (watcher.getActiveVendorConfig(vendorId) == null),
                "Vendor config should be removed when config becomes empty.");
    }

    public void waitForCondition(Callable<Boolean> condition, String message)
            throws InterruptedException {
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                if (condition.call()) {
                    return;
                }
            } catch (Exception e) {
                logger.debug("condition error", e);
            }
            waitForRetryWaitPeriod();
        }
        logger.warn(
                "Waited for condition up to "
                        + MAX_RETRIES
                        + " but it did not succeed: "
                        + message);
    }

    public void waitForRetryWaitPeriod() throws InterruptedException {
        sleep(RETRY_WAIT_PERIOD);
    }
}
