package gr.cytech.sendium.core.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(180)
class MemoryDlrResourceTest {
    @TempDir Path root;

    @Test
    void reportsCapacityHeapCostInIsolatedJvm() throws Exception {
        Path executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path arguments = root.resolve("java.args");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Files.writeString(arguments, "-cp\n\"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n");
        for (int run = 1; run <= 3; run++) {
            Path log = root.resolve("child-" + run + ".log");
            Process child = new ProcessBuilder(executable.toString(), "-Xms512m", "-Xmx512m", "-XX:+UseG1GC",
                    "@" + arguments, MemoryDlrHeapProbe.class.getName()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertThat(child.waitFor(50, TimeUnit.SECONDS)).withFailMessage("Probe timed out:%n%s", Files.readString(log)).isTrue();
            } finally {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            }
            String output = Files.readString(log);
            assertThat(child.exitValue()).withFailMessage("Probe failed:%n%s", output).isZero();
            assertThat(output).contains("memoryDlrProbe|success", "|sources:100000", "|maxHeapBytes:536870912",
                    "|cleanup:empty", "|fullApplication:UNQUALIFIED", "|baselineSamples:", "|occupiedSamples:", "|cleanedSamples:");
            System.out.println("memoryDlrResource|run:" + run + "|" + output.strip());
        }
    }

    @Test
    void cleanupReleasesCompletedCapacityState() throws Exception {
        try (var fixture = new MemoryDlrHeapProbe.Fixture(MemoryDlrHeapProbe.CAPACITY)) {
            fixture.populate();
            fixture.assertOccupied();
            fixture.drain();
            assertThat(fixture.owner.usedCapacity()).isZero();
        }
    }

    @Test
    void fullOccupancyRejectsIndependentAdmission() throws Exception {
        try (var fixture = new MemoryDlrHeapProbe.Fixture(MemoryDlrHeapProbe.CAPACITY)) {
            fixture.populate();
            fixture.assertOverloadAndAliasReuse();
            fixture.assertOccupied();
        }
    }
}
