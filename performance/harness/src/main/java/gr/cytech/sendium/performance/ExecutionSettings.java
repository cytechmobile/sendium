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
    public record ResourceFiles(int schemaVersion, String sendiumSamples, String harnessSamples, String cpuCredits,
            int sampleBudget, String rdsMetrics, String gatewaySamples) {
        public ResourceFiles {
            if (schemaVersion != 1 || sampleBudget < 1) { throw new IllegalArgumentException("Invalid resource inputs"); }
            for (String value : new String[]{sendiumSamples, harnessSamples, cpuCredits, rdsMetrics, gatewaySamples}) {
                if (value != null && value.isBlank()) { throw new IllegalArgumentException("Empty resource input path"); }
            }
            if (sendiumSamples != null && gatewaySamples != null) { throw new IllegalArgumentException("Conflicting gateway resource inputs"); }
        }
        public ResourceFiles(int schemaVersion, String sendiumSamples, String harnessSamples, String cpuCredits, int sampleBudget, String rdsMetrics) {
            this(schemaVersion, sendiumSamples, harnessSamples, cpuCredits, sampleBudget, rdsMetrics, null);
        }
        public ResourceFiles(int schemaVersion, String sendiumSamples, String harnessSamples, String cpuCredits, int sampleBudget) {
            this(schemaVersion, sendiumSamples, harnessSamples, cpuCredits, sampleBudget, null);
        }
        public static ResourceFiles empty() { return new ResourceFiles(1, null, null, null, ResourceEvidence.DEFAULT_SAMPLE_BUDGET); }
        public ResourceEvidence load(Path base, Path localHarnessSamples) {
            return load(base, localHarnessSamples, BenchmarkTarget.SENDIUM);
        }
        public ResourceEvidence load(Path base, Path localHarnessSamples, BenchmarkTarget target) {
            if (target == BenchmarkTarget.KANNEL && sendiumSamples != null) {
                throw new IllegalArgumentException("Use gatewaySamples for Kannel resource evidence");
            }
            return ResourceEvidence.loadAvailable(resolve(base, gatewaySamples == null ? sendiumSamples : gatewaySamples),
                    localHarnessSamples == null ? resolve(base, harnessSamples) : localHarnessSamples,
                    resolve(base, cpuCredits), resolve(base, rdsMetrics), sampleBudget, target);
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
