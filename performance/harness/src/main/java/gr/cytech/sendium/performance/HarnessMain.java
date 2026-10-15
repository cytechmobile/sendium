package gr.cytech.sendium.performance;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Path;

/** Workload validation and isolated runtime configuration generation. */
public final class HarnessMain {
    private HarnessMain() { }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        boolean generate = args.length == 4 && "generate-config".equals(args[0]);
        if (!generate && (args.length != 2 || !"validate-config".equals(args[0]))) {
            System.err.println("Usage: java -jar harness.jar validate-config <run-config.json>");
            System.err.println("   or: java -jar harness.jar generate-config <run-config.json> <deployment.json> <new-output-directory>");
            return 2;
        }
        try {
            ObjectMapper mapper = JsonMapper.builder()
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();
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
}
