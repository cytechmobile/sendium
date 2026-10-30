package gr.cytech.sendium.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

final class KannelFixtures {
    static ResultReport.Context context(String identity) {
        var mapper = new ObjectMapper();
        var app = mapper.createObjectNode();
        app.put("benchmarkTarget", "kannel").put("runtime", "kannel")
                .put("dlrStorage", "internal").put("messageStore", "spool");
        app.putArray("jvmArguments"); app.putObject("connections");
        app.putObject("source").put("version", "1.4.5").put("archiveSha256", "c".repeat(64))
                .put("patchArchiveSha256", "d".repeat(64)).putArray("patches").add("build-fix.patch");
        var infrastructure = mapper.createObjectNode().put("preset", "kannel-unit-fixture").put("region", "local");
        infrastructure.putObject("kannel"); infrastructure.putObject("harness");
        infrastructure.putObject("database").put("provisioned", false);
        return new ResultReport.Context(new ResultReport.Provenance("unit-fixture", null, "b".repeat(40), null,
                identity, List.of(new ResultReport.Artifact("kannel-source-fixture", "c".repeat(64)))),
                app, infrastructure, List.of());
    }
}
