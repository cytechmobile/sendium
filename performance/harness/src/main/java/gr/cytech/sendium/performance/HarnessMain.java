package gr.cytech.sendium.performance;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Path;

/** Validation, isolated generation, benchmark execution, and offline report finalization. */
public final class HarnessMain {
    private HarnessMain() { }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        boolean generate = args.length == 4 && "generate-config".equals(args[0]);
        boolean benchmark = args.length == 4 && "run".equals(args[0]);
        boolean finalize = args.length == 4 && "finalize-report".equals(args[0]);
        if (!generate && !benchmark && !finalize && (args.length != 2 || !"validate-config".equals(args[0]))) {
            System.err.println("Usage: java -jar harness.jar validate-config <run-config.json>");
            System.err.println("   or: java -jar harness.jar generate-config <run-config.json> <deployment.json> <new-output-directory>");
            System.err.println("   or: java -jar harness.jar run <harness-config-directory> <execution.json> <new-output-directory>");
            System.err.println("   or: java -jar harness.jar finalize-report <results.json> <resource-inputs.json> <new-output-directory>");
            return 2;
        }
        try {
            if (benchmark) { return new BenchmarkRunner().run(Path.of(args[1]), Path.of(args[2]), Path.of(args[3])); }
            if (finalize) {
                ObjectMapper mapper = inputMapper();
                var files = mapper.readValue(Path.of(args[2]).toFile(), ExecutionSettings.ResourceFiles.class);
                var writer = new ResultReport();
                var initial = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(Path.of(args[1]).toFile());
                var report = writer.finalizeResources(initial,
                        files.load(Path.of(args[2]).toAbsolutePath().getParent(), null,
                                BenchmarkTarget.from(initial.path("configuration").path("application"))));
                java.nio.file.Files.createDirectory(Path.of(args[3]));
                writer.write(Path.of(args[3]), report);
                System.out.println("Finalized resource evidence: " + report.json().at("/validity/status").asText());
                return report.json().at("/validity/exitCode").asInt();
            }
            ObjectMapper mapper = configurationMapper();
            RunConfig config = mapper.readValue(Path.of(args[1]).toFile(), RunConfig.class);
            if (generate) {
                BenchmarkDeployment deployment = mapper.readValue(Path.of(args[2]).toFile(), BenchmarkDeployment.class);
                BenchmarkConfigGenerator generator = new BenchmarkConfigGenerator();
                generator.write(Path.of(args[3]), generator.generate(config, deployment));
                System.out.println("Generated private runtime bundles and public effective configuration");
            } else { System.out.println(mapper.writeValueAsString(config)); }
            return 0;
        } catch (Exception exception) {
            // Parser messages may contain input content; do not expose configuration secrets.
            System.err.println("Invalid configuration or unavailable output directory");
            return 2;
        }
    }

    static ObjectMapper inputMapper() {
        return JsonMapper.builder().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    }

    static ObjectMapper configurationMapper() {
        return JsonMapper.builder()
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();
    }
}
