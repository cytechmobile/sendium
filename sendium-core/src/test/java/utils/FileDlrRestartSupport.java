package utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs small Java probes inside a Linux container against file-DLR volumes. Only the compiled classes and the few
 * libraries the file adapter needs are staged and mounted, so the host classpath layout does not matter.
 */
public final class FileDlrRestartSupport {
    public static final String JAVA_IMAGE = System.getenv().getOrDefault("SENDIUM_E2E_JAVA_IMAGE", "eclipse-temurin:25-jre");
    // Failsafe puts the packaged module jar on the classpath instead of target/classes.
    private static final List<String> LIBRARIES = List.of("sendium-core-", "sqlite-jdbc-", "slf4j-api-", "jackson-core-",
            "jackson-databind-", "jackson-annotations-");
    private static final String DATABASE = "dlr.db";

    private FileDlrRestartSupport() {
    }

    public record Result(int exitCode, String output) {
    }

    /** Copies compiled classes and adapter libraries into a fresh directory for a read-only container mount. */
    public static Path stageClasspath(Path staging) throws IOException {
        Files.createDirectories(staging);
        int index = 0;
        for (Path path : classpathEntries()) {
            String name = path.getFileName() == null ? "" : path.getFileName().toString();
            if (Files.isDirectory(path) && (name.equals("classes") || name.equals("test-classes"))) {
                copyTree(path, staging.resolve("dir" + index++));
            } else if (Files.isRegularFile(path) && LIBRARIES.stream().anyMatch(name::startsWith)) {
                Files.copy(path, staging.resolve(name));
            }
        }
        return staging;
    }

    // Surefire/Failsafe expose the test classpath as a property; exec:java uses an isolated URL class loader.
    private static List<Path> classpathEntries() {
        List<Path> entries = new ArrayList<>();
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null && Thread.currentThread().getContextClassLoader() instanceof java.net.URLClassLoader loader) {
            for (java.net.URL url : loader.getURLs()) {
                try {
                    entries.add(Path.of(url.toURI()));
                } catch (java.net.URISyntaxException | IllegalArgumentException ignored) {
                    // Non-file entries cannot be staged.
                }
            }
            return entries;
        }
        for (String entry : (classpath == null ? System.getProperty("java.class.path") : classpath)
                .split(java.io.File.pathSeparator)) {
            entries.add(Path.of(entry));
        }
        return entries;
    }

    /** Runs a main class in a disposable container; options can add users, volumes and tmpfs mounts. */
    public static Result runJava(Path staging, List<String> dockerOptions, String mainClass, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("docker", "run", "--rm",
                "-v", staging.toAbsolutePath() + ":/cp:ro", "-e", "JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/tmp"));
        command.addAll(dockerOptions);
        List<String> entries = new ArrayList<>();
        try (Stream<Path> staged = Files.list(staging)) {
            staged.map(path -> "/cp/" + path.getFileName()).sorted().forEach(entries::add);
        }
        command.addAll(List.of(JAVA_IMAGE, "java", "-cp", String.join(":", entries), mainClass));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("fileDlrProbe|error|reason:timeout|main:" + mainClass);
        }
        return new Result(process.exitValue(), new String(output, StandardCharsets.UTF_8));
    }

    /**
     * Container entry point for the smoke harness, run against a stopped retained volume. {@code makeDue} moves
     * scheduled HTTP retries to now, simulating the fixed one-hour retry interval passing; {@code inspect} prints
     * body-free lifecycle columns for assertions.
     */
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[1]);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve(DATABASE));
             Statement statement = connection.createStatement()) {
            switch (args[0]) {
                case "makeDue" -> System.out.println("madeDue:" + statement.executeUpdate("""
                        UPDATE dlr_message SET next_attempt_at = 0
                        WHERE delivery_channel = 'HTTP' AND delivery_status = 'PENDING'
                        """));
                case "inspect" -> {
                    try (ResultSet rows = statement.executeQuery("""
                            SELECT gateway_message_id, delivery_channel, delivery_status, delivery_attempt_count,
                                ifnull(last_delivery_result, '-')
                            FROM dlr_message ORDER BY gateway_message_id
                            """)) {
                        while (rows.next()) {
                            System.out.println("row:" + rows.getString(1) + "|" + rows.getString(2) + "|" +
                                    rows.getString(3) + "|" + rows.getInt(4) + "|" + rows.getString(5));
                        }
                    }
                }
                default -> throw new IllegalArgumentException("Unknown file-DLR helper command");
            }
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> files = Files.walk(source)) {
            for (Path path : files.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
    }
}
