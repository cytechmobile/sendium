package gr.cytech.sendium.core.worker;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import utils.FileDlrRestartSupport;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static gr.cytech.sendium.core.worker.FileDlrTestSupport.httpState;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Linux faults in a disposable container, run as a non-root user: a size-limited tmpfs for disk full,
 * POSIX permissions, a second process for ownership, and invalid headers/schemas. Docker is required; the test
 * fails rather than skips when it is unavailable.
 */
@Timeout(600)
class FileDlrFaultRecoveryIT {
    private static final List<String> NON_ROOT = List.of("--user", "1000:1000",
            "--tmpfs", "/full:size=512k,uid=1000,gid=1000,mode=0700",
            "--tmpfs", "/data:size=64m,uid=1000,gid=1000,mode=0700",
            // The driver extracts its native library to java.io.tmpdir, which must allow execution.
            "--tmpfs", "/tmp:size=64m,mode=1777,exec");

    @TempDir static Path staging;

    @BeforeAll
    static void stage() throws IOException {
        FileDlrRestartSupport.stageClasspath(staging.resolve("cp"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"diskFull", "unwritable", "locked", "incompatible", "corruptHeader"})
    void operationalFailuresDoNotResetAcknowledgedState(String fault) throws Exception {
        var result = FileDlrRestartSupport.runJava(staging.resolve("cp"), NON_ROOT, FaultProbe.class.getName(), fault);
        result.output().lines().filter(line -> line.startsWith(fault + "|")).forEach(System.out::println);
        assertThat(result.output()).as(result.output()).contains(fault + "|success");
        assertThat(result.exitCode()).as(result.output()).isZero();
    }

    /** Runs inside the container; a non-zero exit or missing success line fails the case. */
    public static final class FaultProbe {
        public static void main(String[] args) throws Exception {
            Path data = Path.of("/data");
            requireNonRoot(data);
            switch (args[0]) {
                case "diskFull" -> diskFull(Path.of("/full/dlr"), data.resolve("dlr"));
                case "unwritable" -> unwritable(data.resolve("dlr"));
                case "locked" -> locked(data.resolve("dlr"));
                case "incompatible" -> incompatible(data.resolve("dlr"));
                case "corruptHeader" -> corruptHeader(data.resolve("dlr"));
                case "lockAttempt" -> System.exit(attemptOpen(Path.of(args[1])));
                default -> throw new IllegalArgumentException(args[0]);
            }
            System.out.println(args[0] + "|success");
        }

        private static void diskFull(Path full, Path roomy) throws Exception {
            List<String> acknowledged = new ArrayList<>();
            DlrStorageException failure = null;
            try (var storage = FileDlrStorage.open(full)) {
                String padding = "x".repeat(2_000);
                while (failure == null) {
                    MessageState state = new MessageState(java.util.UUID.randomUUID().toString(), "account",
                            "system", "from", "to", "https://callback.test/" + padding);
                    state.setDeliveryChannel(MessageState.DeliveryChannel.HTTP);
                    try {
                        storage.recordProviderAccepted(state, "P", state.getGatewayMsgId());
                        acknowledged.add(state.getGatewayMsgId());
                    } catch (DlrStorageException e) {
                        failure = e;
                    }
                }
                check(failure.getMessage().contains("sqliteCode13"), "disk full reported: " + failure.getMessage());
                check(threw(() -> storage.getState(acknowledged.getFirst())), "store stays unavailable after ENOSPC");
            }
            // In place without headroom: either refused or intact, never reset.
            try (var reopened = FileDlrStorage.open(full)) {
                check(reopened.getState(acknowledged.getLast()).isPresent(), "in-place reopen kept acknowledged state");
            } catch (DlrStorageException refused) {
                System.out.println("diskFull|inPlace:refused|reason:" + refused.getMessage());
            }
            // The stopped namespace, copied with any journal to a volume with headroom, keeps every acknowledgement.
            copyTree(full, roomy);
            try (var recovered = FileDlrStorage.open(roomy)) {
                for (String id : acknowledged) {
                    check(recovered.getState(id).isPresent(), "acknowledged receipt survived: " + id);
                }
                recovered.recordProviderAccepted(httpState(), "P", "after-headroom");
            }
            System.out.println("diskFull|acknowledged:" + acknowledged.size());
        }

        private static void unwritable(Path directory) throws Exception {
            MessageState state = seed(directory);
            String before = digest(directory);
            setPermissions(directory, "r-x------", "r--------");
            check(threw(() -> Files.createFile(directory.resolve("probe"))), "permissions are enforced (non-root)");
            check(threw(() -> FileDlrStorage.open(directory).close()), "read-only namespace refused");
            setPermissions(directory, "rwx------", "rw-------");
            check(before.equals(digest(directory)), "refused open left files unchanged");
            try (var storage = FileDlrStorage.open(directory)) {
                check(storage.getState(state.getGatewayMsgId()).isPresent(), "state intact after permissions restored");
            }
        }

        private static void locked(Path directory) throws Exception {
            MessageState state = seed(directory);
            try (var owner = FileDlrStorage.open(directory)) {
                String java = ProcessHandle.current().info().command().orElseThrow();
                var second = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                        FaultProbe.class.getName(), "lockAttempt", directory.toString()).inheritIO().start();
                check(second.waitFor(60, TimeUnit.SECONDS), "second process finished");
                check(second.exitValue() == 3, "second process saw ownership conflict, exit " + second.exitValue());
                check(owner.getState(state.getGatewayMsgId()).isPresent(), "owner keeps serving");
            }
        }

        private static int attemptOpen(Path directory) {
            try (var ignored = FileDlrStorage.open(directory)) {
                return 0;
            } catch (DlrStorageException e) {
                return e.getMessage().contains("ownershipConflict") ? 3 : 4;
            }
        }

        private static void incompatible(Path directory) throws Exception {
            seed(directory);
            for (String pragma : List.of("PRAGMA user_version = 99", "PRAGMA application_id = 7")) {
                try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("dlr.db"));
                     Statement statement = connection.createStatement()) {
                    statement.execute(pragma);
                }
                String before = digest(directory);
                check(threw(() -> FileDlrStorage.open(directory).close()), "incompatible schema refused: " + pragma);
                check(before.equals(digest(directory)), "refused open left files unchanged: " + pragma);
                try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("dlr.db"));
                     Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA user_version = " + FileDlrStorage.SCHEMA_VERSION);
                    statement.execute("PRAGMA application_id = " + FileDlrStorage.APPLICATION_ID);
                }
            }
        }

        private static void corruptHeader(Path directory) throws Exception {
            seed(directory);
            Path database = directory.resolve("dlr.db");
            byte[] bytes = Files.readAllBytes(database);
            byte[] damaged = bytes.clone();
            System.arraycopy("NOT A DATABASE!!".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, damaged, 0, 16);
            Files.write(database, damaged);
            String before = digest(directory);
            try {
                FileDlrStorage.open(directory).close();
                throw new IllegalStateException("corrupt header was accepted");
            } catch (DlrStorageException refused) {
                check(refused.getMessage().contains("sqliteCode26"), "SQLite reported not-a-database: " +
                        refused.getMessage());
            }
            check(before.equals(digest(directory)), "corrupt database was not reset or replaced");
        }

        private static MessageState seed(Path directory) {
            MessageState state = httpState();
            try (var storage = FileDlrStorage.open(directory)) {
                storage.recordProviderAccepted(state, "P", "seed");
            }
            return state;
        }

        private static void requireNonRoot(Path data) throws IOException {
            Path probe = Files.createDirectories(data.resolve("root-probe"));
            Files.setPosixFilePermissions(probe, PosixFilePermissions.fromString("r-x------"));
            check(threw(() -> Files.createFile(probe.resolve("x"))), "probe runs without root privileges");
            Files.setPosixFilePermissions(probe, PosixFilePermissions.fromString("rwx------"));
        }

        private static void setPermissions(Path directory, String dir, String file) throws IOException {
            try (Stream<Path> files = Files.list(directory)) {
                for (Path path : files.toList()) {
                    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(file));
                }
            }
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString(dir));
        }

        private static String digest(Path directory) throws Exception {
            var sha = MessageDigest.getInstance("SHA-256");
            try (Stream<Path> files = Files.list(directory)) {
                for (Path path : files.sorted().toList()) {
                    sha.update(path.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    sha.update(Files.readAllBytes(path));
                }
            }
            return HexFormat.of().formatHex(sha.digest());
        }

        private static void copyTree(Path source, Path target) throws IOException {
            Files.createDirectories(target);
            try (Stream<Path> files = Files.list(source)) {
                for (Path path : files.toList()) {
                    Files.copy(path, target.resolve(path.getFileName().toString()));
                }
            }
        }

        private static boolean threw(ThrowingRunnable action) {
            try {
                action.run();
                return false;
            } catch (Exception | Error e) {
                return !(e instanceof AssertionError);
            }
        }

        private static void check(boolean condition, String what) {
            if (!condition) {
                throw new IllegalStateException("fileDlrFault|error|check:" + what);
            }
        }

        private interface ThrowingRunnable {
            void run() throws Exception;
        }
    }
}
