package gr.cytech.sendium.core.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static gr.cytech.sendium.core.worker.FileDlrTestSupport.execute;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.httpState;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.raw;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.single;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class FileDlrOwnershipTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {"second-owner", "unknown-schema", "foreign-database", "missing-schema-object",
        "zero-length", "invalid-header"})
    void rejectsUnsafeStoresWithoutFallback(String unsafe) throws Exception {
        Path database = root.resolve(FileDlrStorage.DATABASE_FILE);
        MessageState existing = httpState();
        if (!unsafe.equals("zero-length") && !unsafe.equals("foreign-database")) {
            try (var storage = FileDlrStorage.open(root)) {
                storage.recordProviderAccepted(existing, "P", "X");
            }
        }
        switch (unsafe) {
            case "second-owner" -> {
                try (var owner = FileDlrStorage.open(root)) {
                    assertThatThrownBy(() -> FileDlrStorage.open(root))
                            .hasMessage("fileDlrOpen|error|reason:ownershipConflict");
                    assertThat(owner.correlationOwner("P", "X")).contains(existing.getGatewayMsgId());
                }
                return;
            }
            case "unknown-schema" -> execute(root, "PRAGMA user_version = 999");
            case "foreign-database" -> {
                Files.createDirectories(root);
                execute(root, "CREATE TABLE unrelated (value TEXT)");
            }
            case "missing-schema-object" -> execute(root, "DROP INDEX provider_correlation_gateway_idx");
            case "zero-length" -> {
                Files.createDirectories(root);
                Files.createFile(database);
            }
            case "invalid-header" -> {
                byte[] bytes = Files.readAllBytes(database);
                for (int i = 0; i < 16; i++) {
                    bytes[i] = 0;
                }
                Files.write(database, bytes);
            }
            default -> throw new IllegalArgumentException(unsafe);
        }
        byte[] before = Files.readAllBytes(database);

        assertThatThrownBy(() -> FileDlrStorage.open(root)).isInstanceOf(DlrStorageException.class);

        // Rejection never resets or replaces the existing database, and releases ownership.
        assertThat(Files.readAllBytes(database)).isEqualTo(before);
        assertThatThrownBy(() -> FileDlrStorage.open(root)).isInstanceOf(DlrStorageException.class)
                .hasMessageNotContaining("ownershipConflict");
    }

    @Test
    void startupWriteProbeMustCommitBeforeReady() throws Exception {
        MessageState existing = httpState();
        try (var storage = FileDlrStorage.open(root)) {
            storage.recordProviderAccepted(existing, "P", "X");
        }
        Path database = root.resolve(FileDlrStorage.DATABASE_FILE);
        assertThat(database.toFile().setWritable(false)).isTrue();
        try {
            assumeFalse(Files.isWritable(database), "privileged user ignores file permissions");
            assertThatThrownBy(() -> FileDlrStorage.open(root))
                    .isInstanceOf(DlrStorageException.class)
                    .hasMessageContaining("startup probe");
        } finally {
            assertThat(database.toFile().setWritable(true)).isTrue();
        }
        try (var storage = FileDlrStorage.open(root)) {
            assertThat(storage.correlationOwner("P", "X")).contains(existing.getGatewayMsgId());
        }
    }

    @Test
    void requiresDeleteJournalAndFullSynchronization() throws Exception {
        try (var storage = FileDlrStorage.open(root)) {
            storage.recordProviderAccepted(httpState(), "P", "X");
        }
        try (Connection connection = raw(root)) {
            assertThat(single(connection, "PRAGMA journal_mode = WAL")).isEqualToIgnoringCase("wal");
        }
        List<String> observed = new ArrayList<>();
        try (var storage = FileDlrStorage.open(root)) {
            storage.beforeCommit = c -> {
                observed.add(single(c, "PRAGMA journal_mode"));
                observed.add(single(c, "PRAGMA synchronous"));
            };
            storage.recordProviderAccepted(httpState(), "P", "Y");
        }
        assertThat(observed).containsExactly("delete", "2");
        try (Connection connection = raw(root)) {
            assertThat(single(connection, "PRAGMA journal_mode")).isEqualToIgnoringCase("delete");
        }
        assertThat(root.resolve(FileDlrStorage.DATABASE_FILE + "-wal")).doesNotExist();
    }
}
