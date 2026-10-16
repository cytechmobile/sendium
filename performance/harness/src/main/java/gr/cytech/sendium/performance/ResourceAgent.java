package gr.cytech.sendium.performance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Owns only the local harness sampler; remote application sampling is deployment-owned. */
final class ResourceAgent implements AutoCloseable {
    private final Process process;
    private final double interval;
    private final Path output;

    ResourceAgent(ExecutionSettings.Sampling settings, RunConfig workload, Path base, Path directory) throws Exception {
        interval = settings.intervalSeconds();
        double duration = (long) workload.warmupSeconds() + workload.measurementSeconds() + workload.drainTimeoutSeconds() + 5 + 2 * interval;
        if (Math.ceil(duration / interval) + 1 > settings.maxSamples()) { throw new IllegalArgumentException("Sampler budget cannot cover the run"); }
        output = directory.resolve("harness-resources.jsonl");
        process = new ProcessBuilder(settings.pythonExecutable(), ExecutionSettings.resolve(base, settings.agentPath()).toString(),
                "--pid", Long.toString(ProcessHandle.current().pid()), "--duration-seconds", Double.toString(duration),
                "--interval-seconds", Double.toString(interval), "--max-samples", Integer.toString(settings.maxSamples()),
                "--output", output.toString()).redirectErrorStream(true).redirectOutput(directory.resolve("resource-agent.log").toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((!Files.exists(output) || Files.size(output) == 0) && process.isAlive() && System.nanoTime() < deadline) { Thread.sleep(20); }
            if (!process.isAlive() || !Files.exists(output) || Files.size(output) == 0) {
                throw new IllegalStateException("Resource agent did not produce a baseline");
            }
        } catch (Exception exception) { close(); throw exception; }
    }

    Path output() { return output; }
    boolean completedSuccessfully() { return !process.isAlive() && process.exitValue() == 0; }

    @Override public void close() throws Exception {
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor((long) Math.ceil(interval) + 5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Resource agent did not stop"); }
            }
        }
    }
}
