package utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.LongSupplier;

/** Optional test-harness metadata. Never records payloads, exception bodies or environment values. */
final class ScenarioTiming {
    private final Path output;
    private final String runtime;
    private final String backend;
    private final LongSupplier clock;

    ScenarioTiming(Path output, String runtime, String backend, LongSupplier clock) {
        this.output = output;
        this.runtime = runtime;
        this.backend = backend;
        this.clock = clock;
    }

    static ScenarioTiming fromEnvironment(String backend) {
        Path output = null;
        String configured = System.getenv("SENDIUM_E2E_TIMING_FILE");
        if (configured != null && !configured.isBlank()) {
            try {
                output = Path.of(configured);
            } catch (InvalidPathException invalid) {
                warn();
            }
        }
        String runtime = System.getenv("SENDIUM_E2E_TIMING_RUNTIME");
        if (!"jvm".equals(runtime) && !"native".equals(runtime)) {
            runtime = "unknown";
        }
        return new ScenarioTiming(output, runtime, backend, System::nanoTime);
    }

    void run(String scenario, Action action) throws Exception {
        call(scenario, () -> {
            action.run();
            return null;
        });
    }

    <T> T call(String scenario, Operation<T> operation) throws Exception {
        if (output == null) {
            return operation.run();
        }
        write(scenario, "started", "");
        long started = clock.getAsLong();
        boolean passed = false;
        try {
            T result = operation.run();
            passed = true;
            return result;
        } finally {
            double seconds = (clock.getAsLong() - started) / 1_000_000_000.0;
            write(scenario, passed ? "passed" : "failed", Double.toString(seconds));
        }
    }

    private void write(String scenario, String outcome, String seconds) {
        try {
            Files.writeString(output, runtime + "\t" + backend + "\t" + scenario + "\t" + outcome + "\t" + seconds + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | SecurityException failure) {
            warn();
        }
    }

    private static void warn() {
        System.err.println("scenarioTiming|incomplete|reason:outputUnavailable");
    }

    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }

    @FunctionalInterface
    interface Operation<T> {
        T run() throws Exception;
    }
}
