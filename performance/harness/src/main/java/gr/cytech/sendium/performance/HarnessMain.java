package gr.cytech.sendium.performance;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Path;

/** Configuration validation entry point; protocol execution is added in Phase 2. */
public final class HarnessMain {
    private HarnessMain() { }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length != 2 || !"validate-config".equals(args[0])) {
            System.err.println("Usage: java -jar harness.jar validate-config <run-config.json>");
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
            System.out.println(mapper.writeValueAsString(config));
            return 0;
        } catch (Exception exception) {
            // Parser messages may contain input content; do not expose configuration secrets.
            System.err.println("Invalid or unreadable workload configuration");
            return 2;
        }
    }
}
