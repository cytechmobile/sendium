package gr.cytech.sendium.performance;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HarnessMainTest {
    @TempDir Path directory;

    @Test
    void validatesResolvedConfigurationAndRejectsMissingOrCoercedSettings() throws Exception {
        String fixture = Files.readString(Path.of("src/test/resources/run-config.json"));
        Path file = directory.resolve("config.json");
        Files.writeString(file, fixture);
        assertEquals(0, HarnessMain.run(new String[]{"validate-config", file.toString()}));
        for (String invalid : new String[]{
                fixture.replace("\"dlrEnabled\": true", "\"dlrEnabled\": \"true\""),
                fixture.replace("\"workerTps\": 0", "\"workerTps\": 0.5"),
                fixture.replace("\"workerTps\": 0", "\"workerTps\": -1"),
                fixture.replace("  \"dlrEnabled\": true,", ""),
                fixture + " {}"}) {
            Files.writeString(file, invalid);
            assertEquals(2, HarnessMain.run(new String[]{"validate-config", file.toString()}));
        }
    }
}
