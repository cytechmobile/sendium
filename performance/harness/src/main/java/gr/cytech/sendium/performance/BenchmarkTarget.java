package gr.cytech.sendium.performance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Locale;

/** Target identity and wire readiness; an absent target preserves existing Sendium runs. */
public enum BenchmarkTarget {
    SENDIUM("Sendium"), KANNEL("Kannel");

    private final String displayName;
    BenchmarkTarget(String displayName) { this.displayName = displayName; }
    public String id() { return name().toLowerCase(Locale.ROOT); }
    public String displayName() { return displayName; }
    public HttpGatewayProtocol httpProtocol() {
        return this == KANNEL ? HttpGatewayProtocol.KANNEL : HttpGatewayProtocol.SENDIUM;
    }

    public static BenchmarkTarget from(JsonNode document) {
        if (!document.has("benchmarkTarget")) { return SENDIUM; }
        JsonNode target = document.path("benchmarkTarget");
        if (target.isTextual()) {
            for (BenchmarkTarget value : values()) { if (value.id().equals(target.asText())) { return value; } }
        }
        throw new IllegalArgumentException("Unsupported benchmark target");
    }

    public void validateWorkload(RunConfig workload) {
        if (this == KANNEL && !"http".equals(workload.ingressMode())) {
            throw new IllegalArgumentException("Kannel supports HTTP ingress only");
        }
    }

    void validateApplication(JsonNode application, ResultReport.Provenance provenance) {
        if (this == SENDIUM) {
            if (provenance.applicationCommit() == null || application.has("runtime")
                    && !java.util.List.of("jvm", "native").contains(application.path("runtime").asText())
                    || !java.util.List.of("postgresql", "disabled").contains(application.path("dlrStorage").asText())) {
                throw new IllegalArgumentException("Invalid Sendium application identity");
            }
            return;
        }
        JsonNode source = application.path("source");
        if (provenance.applicationCommit() != null || !"kannel".equals(application.path("runtime").asText())
                || !application.path("jvmArguments").isArray() || !application.path("jvmArguments").isEmpty()
                || application.has("nativeArguments") || application.has("nativeBuild")
                || !"internal".equals(application.path("dlrStorage").asText())
                || !java.util.List.of("spool", "file", "disabled").contains(application.path("messageStore").asText())
                || !source.path("version").isTextual() || source.path("version").asText().isBlank()
                || source.path("version").asText().length() > 128
                || !source.path("archiveSha256").isTextual() || !source.path("patchArchiveSha256").isTextual()
                || !source.path("archiveSha256").asText().matches("[a-f0-9]{64}")
                || !source.path("patchArchiveSha256").asText().matches("[a-f0-9]{64}")
                || !source.path("patches").isArray() || source.path("patches").size() > 32) {
            throw new IllegalArgumentException("Invalid Kannel source, runtime or storage identity");
        }
        for (JsonNode patch : source.path("patches")) {
            if (!patch.isTextual() || patch.asText().isBlank()) {
                throw new IllegalArgumentException("Invalid Kannel patch identity");
            }
        }
    }

    boolean ready(int status, String body, ObjectMapper mapper) throws IOException {
        if (this == KANNEL) {
            // This exercises smsbox's actual sendsms handler without admitting a probe SMS.
            return status == 403 && "Authorization failed for sendsms".equals(body.strip());
        }
        if (status != 200) { return false; }
        JsonNode health = mapper.readTree(body);
        if (health == null || !"UP".equals(health.path("status").asText())) { return false; }
        for (JsonNode check : health.path("checks")) {
            if ("sendium-dlr-storage".equals(check.path("name").asText())
                    && "UP".equals(check.path("status").asText())
                    && "postgresql".equals(check.path("data").path("backend").asText())) { return true; }
        }
        return false;
    }
}
