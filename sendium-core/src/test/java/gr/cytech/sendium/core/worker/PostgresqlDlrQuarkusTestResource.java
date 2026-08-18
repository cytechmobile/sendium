package gr.cytech.sendium.core.worker;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class PostgresqlDlrQuarkusTestResource implements QuarkusTestResourceLifecycleManager {
    private static PostgreSQLContainer postgresql;
    private static int smppPort;
    private Path smppConfiguration;

    @Override
    public Map<String, String> start() {
        if (!Boolean.getBoolean("sendium.postgresql.tests")) {
            return Map.of();
        }

        postgresql = new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("sendium")
                .withUsername("sendium")
                .withPassword("sendium-test");
        postgresql.start();
        smppPort = findFreePort();
        smppConfiguration = createSmppConfiguration(smppPort);
        String jdbcUrl = postgresql.getJdbcUrl() + "&connectTimeout=2&socketTimeout=2";
        return Map.ofEntries(
                Map.entry("sendium.dlr.storage", "postgresql"),
                Map.entry("quarkus.datasource.dlr.active", "true"),
                Map.entry("quarkus.flyway.dlr.active", "true"),
                Map.entry("quarkus.flyway.dlr.migrate-at-start", "true"),
                Map.entry("quarkus.datasource.dlr.jdbc.url", jdbcUrl),
                Map.entry("quarkus.datasource.dlr.username", postgresql.getUsername()),
                Map.entry("quarkus.datasource.dlr.password", postgresql.getPassword()),
                Map.entry("smsg.properties.file.path", smppConfiguration.toString()));
    }

    @Override
    public void stop() {
        if (postgresql != null) {
            postgresql.stop();
            postgresql = null;
        }
        if (smppConfiguration != null) {
            try {
                Files.deleteIfExists(smppConfiguration);
            } catch (IOException ignored) {
                // Temporary test configuration is also cleaned by the operating system.
            }
        }
    }

    static void pausePostgresql() {
        postgresql.getDockerClient().pauseContainerCmd(postgresql.getContainerId()).exec();
    }

    static void resumePostgresql() {
        postgresql.getDockerClient().unpauseContainerCmd(postgresql.getContainerId()).exec();
    }

    static int getSmppPort() {
        return smppPort;
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Could not allocate an SMPP test port", e);
        }
    }

    private static Path createSmppConfiguration(int port) {
        try {
            String configuration = Files.readString(Path.of("src", "test", "resources", "smsg.properties"))
                    .replace("outSms.instance.smpp.enable = truef", "outSms.instance.smpp.enable = true")
                    .replace("outSms.instance.smpp.srv.port = 27777",
                            "outSms.instance.smpp.srv.port = " + port);
            Path temporaryConfiguration = Files.createTempFile("sendium-postgresql-", ".properties");
            Files.writeString(temporaryConfiguration, configuration);
            return temporaryConfiguration;
        } catch (IOException e) {
            throw new IllegalStateException("Could not create the SMPP test configuration", e);
        }
    }
}
