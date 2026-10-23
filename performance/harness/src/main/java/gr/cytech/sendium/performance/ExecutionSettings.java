package gr.cytech.sendium.performance;

import java.nio.file.Path;

/** Public run identity/preset plus optional agent/evidence locations. Runtime credentials stay separate. */
public record ExecutionSettings(int schemaVersion, int readinessTimeoutSeconds, int retentionSeconds,
        int intervalBudget, ResultReport.Context context, ResourceFiles resources, Sampling sampling) {
    public ExecutionSettings {
        if (schemaVersion != 1 || readinessTimeoutSeconds < 1 || retentionSeconds < 0 || intervalBudget < 1 || context == null) {
            throw new IllegalArgumentException("Invalid execution settings");
        }
        if (resources == null) { resources = ResourceFiles.empty(); }
    }
    public record ResourceFiles(int schemaVersion, String sendiumSamples, String harnessSamples, String cpuCredits, int sampleBudget, String rdsMetrics) {
        public ResourceFiles {
            if (schemaVersion != 1 || sampleBudget < 1) { throw new IllegalArgumentException("Invalid resource inputs"); }
            for (String value : new String[]{sendiumSamples, harnessSamples, cpuCredits, rdsMetrics}) {
                if (value != null && value.isBlank()) { throw new IllegalArgumentException("Empty resource input path"); }
            }
        }
        public ResourceFiles(int schemaVersion, String sendiumSamples, String harnessSamples, String cpuCredits, int sampleBudget) {
            this(schemaVersion, sendiumSamples, harnessSamples, cpuCredits, sampleBudget, null);
        }
        public static ResourceFiles empty() { return new ResourceFiles(1, null, null, null, ResourceEvidence.DEFAULT_SAMPLE_BUDGET); }
        public ResourceEvidence load(Path base, Path localHarnessSamples) {
            return ResourceEvidence.loadAvailable(resolve(base, sendiumSamples),
                    localHarnessSamples == null ? resolve(base, harnessSamples) : localHarnessSamples,
                    resolve(base, cpuCredits), resolve(base, rdsMetrics), sampleBudget);
        }
    }
    public record Sampling(String pythonExecutable, String agentPath, double intervalSeconds, int maxSamples) {
        public Sampling {
            if (pythonExecutable == null || pythonExecutable.isBlank() || agentPath == null || agentPath.isBlank()
                    || !Double.isFinite(intervalSeconds) || intervalSeconds <= 0 || intervalSeconds > 60 || maxSamples < 2) {
                throw new IllegalArgumentException("Invalid sampling settings");
            }
        }
    }
    static Path resolve(Path base, String value) { return value == null ? null : base.resolve(value).toAbsolutePath().normalize(); }
}
