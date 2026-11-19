package gr.cytech.sendium.core.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static gr.cytech.sendium.core.worker.FileDlrTestSupport.seed;
import static org.assertj.core.api.Assertions.assertThat;

class FileDlrResourceTest {
    private static final int SYSTEMS = 50;
    private static final int PER_SYSTEM = 667;
    private static final int DUE_HTTP = 33_333;
    private static final int FAILED = 33_333;
    private static final int BACKLOG = DUE_HTTP + FAILED + SYSTEMS * PER_SYSTEM;

    @TempDir
    Path root;

    @Test
    @Timeout(120)
    void boundedBatchesHandleRepresentativeBacklog() throws Exception {
        FileDlrStorage.open(root).close();
        seed(root, DUE_HTTP, "HTTP", "PENDING", "system", 0);
        seed(root, FAILED, "HTTP", "FAILED", "system", 0);
        for (int i = 0; i < SYSTEMS; i++) {
            seed(root, PER_SYSTEM, "SMPP", "PENDING", "system-" + i, 0);
        }
        assertThat(BACKLOG).isGreaterThanOrEqualTo(100_000);

        Path executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path arguments = root.resolve("java.args");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Files.writeString(arguments, "-cp\n\"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n");
        Path log = root.resolve("child.log");
        Process child = new ProcessBuilder(executable.toString(), "-Xmx128m", "@" + arguments,
                BacklogWorker.class.getName(), root.toString()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            assertThat(child.waitFor(100, TimeUnit.SECONDS)).withFailMessage("Child timed out:%n%s",
                    Files.readString(log)).isTrue();
        } finally {
            child.destroyForcibly();
        }
        assertThat(child.exitValue()).withFailMessage("Child failed:%n%s", Files.readString(log)).isZero();
        String output = Files.readString(log);
        assertThat(output).contains("backlog ok");
        System.out.println("fileDlrResource|" + output.substring(output.indexOf("backlog ok")).strip());
    }

    public static final class BacklogWorker {
        public static void main(String[] args) throws java.io.IOException {
            Path database = Path.of(args[0]).resolve(FileDlrStorage.DATABASE_FILE);
            long before = Files.size(database);
            try (var storage = FileDlrStorage.open(Path.of(args[0]))) {
                check(storage.readMetricSnapshot(BACKLOG, Duration.ofSeconds(30)).orElseThrow()
                        .equals(new DlrStorageBackend.ReceiptCounts(0, DUE_HTTP + SYSTEMS * PER_SYSTEM, FAILED)),
                        "snapshot");
                check(storage.claimDueHttpDeliveries(5_000).size() == 1_000, "claim batch is bounded");
                for (int i = 0; i < SYSTEMS; i++) {
                    check(storage.listPendingSmppDeliveries("system-" + i).size() == PER_SYSTEM, "SMPP replay");
                }
                int expired = 0;
                int deleted;
                do {
                    deleted = storage.expireFailures(1_000);
                    expired += deleted;
                } while (deleted == 1_000);
                check(expired == FAILED, "expired " + expired);
                check(storage.readMetricSnapshot(BACKLOG, Duration.ofSeconds(30)).orElseThrow().failedReturn() == 0,
                        "failures drained");
            }
            // Deleted pages are reused, not returned to the file system; no shrinkage is expected.
            System.out.printf("backlog ok|fileBytesBefore:%d|fileBytesAfter:%d|maxHeapBytes:%d%n", before,
                    Files.size(database), Runtime.getRuntime().maxMemory());
        }

        private static void check(boolean condition, String what) {
            if (!condition) {
                throw new IllegalStateException("backlog check failed: " + what);
            }
        }
    }
}
