package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.worker.FileDlrTestSupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.arc.InjectableInstance;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static gr.cytech.sendium.core.worker.FileDlrTestSupport.seed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileDlrDiagnosticsTest {
    // operation|status followed by key:value fields whose values cannot contain delimiters or line breaks.
    private static final String STRUCTURED = "[A-Za-z]+\\|[a-z]+(\\|[A-Za-z]+:[^|\\r\\n]*)*";
    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    @TempDir
    Path root;
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            messages.add(record instanceof ExtLogRecord ext ? ext.getFormattedMessage() :
                new SimpleFormatter().formatMessage(record));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    // Strong references keep the configured levels; CI runs with QUARKUS_LOG_LEVEL=ERROR, which would
    // otherwise filter the INFO/WARN events under test before they reach the handler.
    private final List<Logger> loggers = List.of(Logger.getLogger(ManagedDlrStorage.class.getName()),
            Logger.getLogger(FileDlrStorage.class.getName()));
    private final List<Level> previousLevels = new java.util.ArrayList<>();

    @BeforeEach
    void attach() {
        for (Logger logger : loggers) {
            previousLevels.add(logger.getLevel());
            logger.setLevel(Level.ALL);
            logger.addHandler(capture);
        }
    }

    @AfterEach
    void detach() {
        for (int i = 0; i < loggers.size(); i++) {
            loggers.get(i).removeHandler(capture);
            loggers.get(i).setLevel(previousLevels.get(i));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnosticsAreStructuredAndSanitized() throws Exception {
        // Windows forbids pipes and line breaks in file names; elsewhere they must not split the log line.
        Path directory = root.resolve(WINDOWS ? "dlr store" : "dlr a|b\r\nc");
        ManagedDlrStorage managed = new ManagedDlrStorage();
        managed.postgresqlDataSource = mock(InjectableInstance.class, RETURNS_DEEP_STUBS);
        when(managed.postgresqlDataSource.getHandle().getBean().isActive()).thenReturn(false);
        managed.meterRegistry = new SimpleMeterRegistry();
        managed.deliveryClaimDuration = Duration.ofMinutes(5);
        managed.selectedBackend = "file";
        managed.filePath = java.util.Optional.of(directory.toString());
        try {
            managed.initialize();
        } finally {
            managed.close();
        }

        // A cleanup unit that cannot publish logs a structured reason, not the receipt or its callback.
        var clock = new MutableClock();
        FileDlrStorage.open(directory, Duration.ofMinutes(5), clock).close();
        seed(directory, 3, "HTTP", "FAILED", "system", 0);
        try (var storage = FileDlrStorage.open(directory, Duration.ofMinutes(5), clock)) {
            storage.beforeCommit = c -> c.close();
            clock.set(Duration.ofDays(8).toMillis());
            MessageState secret = new MessageState(java.util.UUID.randomUUID().toString(), "account", "system",
                    "from", "to", "https://user:password@callback.test/dlr?text=hello");
            secret.setDeliveryChannel(MessageState.DeliveryChannel.HTTP);
            try {
                storage.recordProviderAccepted(secret, "provider", "message-text");
            } catch (DlrStorageException expected) {
                // The store is latched; only the diagnostics are inspected here.
            }
        }

        assertThat(messages).anySatisfy(message -> assertThat(message)
                .startsWith("dlrStorage|selected|backend:file|path:").endsWith("|failedRetention:PT168H"));
        assertThat(messages).anySatisfy(message -> assertThat(message)
                .isEqualTo("fileDlrRetention|error|reason:sqliteCode0"));
        assertThat(messages).allSatisfy(message -> assertThat(message).matches(STRUCTURED)
                .doesNotContain("password").doesNotContain("callback.test").doesNotContain("message-text"));
    }
}
