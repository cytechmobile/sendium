package utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioTimingTest {
    @TempDir
    Path directory;

    @Test
    void recordsFailedScenario() throws Exception {
        Path output = directory.resolve("scenarios.tsv");
        var clock = new AtomicLong();
        var timing = new ScenarioTiming(output, "jvm", "memory", clock::get);
        var failure = new IllegalStateException("private failure body");
        assertThatThrownBy(() -> timing.run("capacity", () -> {
            clock.set(2_000_000_000L);
            throw failure;
        })).isSameAs(failure);
        assertThat(Files.readAllLines(output)).containsExactly(
                "jvm\tmemory\tcapacity\tstarted\t",
                "jvm\tmemory\tcapacity\tfailed\t2.0");
    }

    @Test
    void preservesOutcomeOnWriteFailure() throws Exception {
        var timing = new ScenarioTiming(directory, "jvm", "memory", System::nanoTime);
        var failure = new AssertionError("original failure");
        assertThatThrownBy(() -> timing.run("capacity", () -> {
            throw failure;
        })).isSameAs(failure);
        assertThat(timing.call("success", () -> 37)).isEqualTo(37);
    }

    @Test
    void recordsSuccessAndNestedIntervals() throws Exception {
        Path output = directory.resolve("scenarios.tsv");
        var clock = new AtomicLong();
        var timing = new ScenarioTiming(output, "jvm", "file", clock::get);
        timing.run("packaged", () -> {
            clock.set(1_000_000_000L);
            assertThat(timing.call("retry", () -> {
                clock.set(3_000_000_000L);
                return 42;
            })).isEqualTo(42);
            clock.set(4_000_000_000L);
        });
        assertThat(Files.readAllLines(output)).containsExactly(
                "jvm\tfile\tpackaged\tstarted\t",
                "jvm\tfile\tretry\tstarted\t",
                "jvm\tfile\tretry\tpassed\t2.0",
                "jvm\tfile\tpackaged\tpassed\t4.0");
    }

    @Test
    void disabledOutputStillRunsTheScenario() throws Exception {
        assertThat(new ScenarioTiming(null, "unknown", "memory", System::nanoTime)
                .call("capacity", () -> 42)).isEqualTo(42);
    }
}
