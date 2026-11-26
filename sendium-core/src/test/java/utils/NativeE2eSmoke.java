package utils;

import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppServerConfiguration;
import com.cloudhopper.smpp.SmppServerHandler;
import com.cloudhopper.smpp.SmppServerSession;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.SmppSessionHandler;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppServer;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.BaseBind;
import com.cloudhopper.smpp.pdu.BaseBindResp;
import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.Address;
import com.cloudhopper.smpp.type.SmppChannelException;
import com.cloudhopper.smpp.type.SmppInvalidArgumentException;
import com.cloudhopper.smpp.type.SmppProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sun.net.httpserver.HttpServer;
import io.netty.channel.nio.NioEventLoopGroup;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

public class NativeE2eSmoke {
    private static final String IMAGE = System.getenv().getOrDefault("SENDIUM_E2E_IMAGE", "sendium:native-e2e");
    private static final String POSTGRESQL_JDBC_URL = System.getenv().getOrDefault(
            "SENDIUM_DLR_POSTGRESQL_JDBC_URL", "jdbc:postgresql://host.docker.internal:5432/sendium");
    private static final String POSTGRESQL_USERNAME = System.getenv().getOrDefault(
            "SENDIUM_DLR_POSTGRESQL_USERNAME", "sendium");
    private static final String POSTGRESQL_PASSWORD = System.getenv().getOrDefault(
            "SENDIUM_DLR_POSTGRESQL_PASSWORD", "sendium-test");
    private static final int SENDIUM_HTTP_PORT = 18080;
    private static final int SENDIUM_SMPP_PORT = smppPort(System.getenv("SENDIUM_E2E_SMPP_PORT"));
    private static final int UPSTREAM_SMPP_PORT = 27779;
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final boolean PENDING_RESTART = Boolean.parseBoolean(System.getenv("SENDIUM_E2E_PENDING_RESTART"));
    private static final String STORAGE_PROFILE = storageProfile(System.getenv("SENDIUM_E2E_STORAGE_PROFILE"), PENDING_RESTART);
    // Test-only selector for the harness; file mode runs its own retained-volume DLR scenarios.
    private static final String DLR_BACKEND = dlrBackend(System.getenv("SENDIUM_E2E_DLR_BACKEND"), PENDING_RESTART);
    private static final boolean FILE_DLR = DLR_BACKEND.equals("file");
    private static final boolean MEMORY_DLR = DLR_BACKEND.equals("memory");
    private static final boolean DISABLED_DLR = DLR_BACKEND.equals("disabled");

    static String dlrBackend(String configured, boolean pendingRestart) {
        String backend = configured == null ? "postgresql" : configured;
        if (!List.of("postgresql", "file", "memory", "disabled").contains(backend)) {
            throw new IllegalArgumentException("SENDIUM_E2E_DLR_BACKEND must be postgresql, file, memory or disabled");
        }
        if (backend.equals("file") && pendingRestart) {
            throw new IllegalArgumentException("File DLR smoke runs its own restart scenarios; unset SENDIUM_E2E_PENDING_RESTART");
        }
        if (List.of("memory", "disabled").contains(backend) && pendingRestart) {
            throw new IllegalArgumentException("Process-local DLR smoke runs its own recovery scenarios; unset SENDIUM_E2E_PENDING_RESTART");
        }
        return backend;
    }

    static String storageProfile(String configured, boolean restart) {
        String profile = configured == null ? restart ? "file/memory/memory" : "memory/memory/memory" : configured;
        if (!List.of("memory/memory/memory", "file/memory/memory", "file/file/memory", "file/memory/file", "file/file/file").contains(profile)) {
            throw new IllegalArgumentException("SENDIUM_E2E_STORAGE_PROFILE must name a supported outbound storage profile");
        }
        if (restart && profile.equals("memory/memory/memory")) {
            throw new IllegalArgumentException("SENDIUM_E2E_PENDING_RESTART requires file pending storage");
        }
        if (!restart && !profile.equals("memory/memory/memory")) {
            throw new IllegalArgumentException("File smoke profiles require SENDIUM_E2E_PENDING_RESTART=true");
        }
        return profile;
    }

    static int smppPort(String configured) {
        if (configured == null) {
            return 27777;
        }
        try {
            int port = Integer.parseInt(configured);
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException invalid) {
            // Report a fixed configuration error without echoing an arbitrary environment value.
        }
        throw new IllegalArgumentException("SENDIUM_E2E_SMPP_PORT must be an integer between 1 and 65535");
    }

    public static void main(String[] args) throws Exception {
        String containerName = "sendium-e2e-" + UUID.randomUUID().toString().substring(0, 8);
        String spoolVolume = containerName + "-pending";
        String dlrVolume = containerName + "-dlr";
        Process container = null;

        // File mode fails the first HTTP callback so the persisted retry must survive a replacement.
        try (UpstreamSmppServer upstream = new UpstreamSmppServer(UPSTREAM_SMPP_PORT);
             CallbackServer callbackServer = new CallbackServer(FILE_DLR || MEMORY_DLR ? 1 : 0)) {
            upstream.start();
            callbackServer.start();

            Path workDir = Files.createTempDirectory("sendium-e2e-");
            writeRuntimeConfig(workDir);
            Path helper = null;
            if (FILE_DLR) {
                Process volume = run(List.of("docker", "volume", "create", dlrVolume), true);
                require(volume.waitFor(30, TimeUnit.SECONDS) && volume.exitValue() == 0, "Cannot create retained DLR volume");
                helper = FileDlrRestartSupport.stageClasspath(workDir.resolve("helper-classpath"));
            }
            if (PENDING_RESTART) {
                Process volume = run(List.of("docker", "volume", "create", spoolVolume), true);
                require(volume.waitFor(30, TimeUnit.SECONDS) && volume.exitValue() == 0, "Cannot create retained spool volume");
                Files.writeString(workDir.resolve("conf/smsg.properties"),
                        "\noutSms.instance.smpp.reassembling.timeoutMillis = 120000\n", StandardOpenOption.APPEND);
                if (STORAGE_PROFILE.equals("file/memory/memory")) {
                    Files.writeString(workDir.resolve("conf/smsg.properties"), "\noutSms.pause = true\n", StandardOpenOption.APPEND);
                } else if (STORAGE_PROFILE.endsWith("file")) {
                    Files.writeString(workDir.resolve("conf/smsg.properties"), "\noutSms.instance.route.pause = true\n", StandardOpenOption.APPEND);
                } else {
                    setRoutingGate(workDir, true);
                }
            }
            container = startSendiumContainer(containerName, workDir);
            waitForDlrReadiness();
            waitForPort("localhost", SENDIUM_SMPP_PORT, TIMEOUT);
            require(upstream.awaitSessionBound(), "Sendium container did not bind to the upstream SMPP server");

            if (MEMORY_DLR) {
                verifyMemoryHttpRetry(upstream, callbackServer);
                verifyMemorySmppReconnect(upstream);
                container = verifyMemoryLossOnReplacement(containerName, workDir, upstream, callbackServer);
                require(stopContainer(containerName), "Cannot stop live memory scenario");
                container = null;
                verifyRecoveredMemoryCapacity(upstream, callbackServer);
                verifyNonDlrBeyondBlockedWindow(upstream, callbackServer);
                if (containerUsesJvm()) {
                    verifyMemoryCapacityAtDefault(upstream, callbackServer);
                }
            } else if (DISABLED_DLR) {
                verifyDisabledIngress(upstream, callbackServer);
                require(stopContainer(containerName), "Cannot stop disabled ingress scenario");
                container = null;
                verifyNonDlrBeyondBlockedWindow(upstream, callbackServer);
            } else if (PENDING_RESTART) {
                container = verifyPendingSurvivesReplacement(containerName, workDir, upstream, callbackServer);
            } else if (FILE_DLR) {
                container = verifyFileHttpRetrySurvivesReplacement(containerName, workDir, upstream, callbackServer,
                        helper, dlrVolume);
                container = verifyFileSmppReplaySurvivesReplacement(containerName, workDir, upstream, helper, dlrVolume);
            } else {
                container = verifyHttpCorrelationSurvivesRestart(containerName, workDir, upstream, callbackServer, 1);
                verifySmppSubmitGetsDeliverSm(upstream, 2);
            }
        } catch (Throwable t) {
            if (container != null) {
                printDockerLogs(containerName);
            }
            throw t;
        } finally {
            HTTP.close();
            if (container != null) {
                stopContainer(containerName);
                container.destroyForcibly();
            }
            if (PENDING_RESTART) {
                run(List.of("docker", "volume", "rm", spoolVolume), true).waitFor(30, TimeUnit.SECONDS);
            }
            if (FILE_DLR) {
                run(List.of("docker", "volume", "rm", dlrVolume), true).waitFor(30, TimeUnit.SECONDS);
            }
        }
    }

    private static boolean containerUsesJvm() throws Exception {
        var inspect = new ProcessBuilder("docker", "image", "inspect", "--format", "{{json .Config.Cmd}}", IMAGE).start();
        String command = new String(inspect.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        require(inspect.waitFor(10, TimeUnit.SECONDS) && inspect.exitValue() == 0, "Cannot identify image runtime");
        require(command.contains("java") || command.contains("./application"), "Unrecognized packaged image command");
        return command.contains("java");
    }

    private static void verifyMemoryCapacityAtDefault(UpstreamSmppServer upstream, CallbackServer callbacks) throws Exception {
        String name = "sendium-e2e-capacity-" + UUID.randomUUID().toString().substring(0, 8);
        Path work = Files.createTempDirectory("sendium-default-capacity-");
        writeRuntimeConfig(work);
        int first = upstream.receivedSubmits.get() + 1;
        upstream.setAutomaticDelivery(false);
        try {
            // Deliberately omit the capacity setting: this must qualify the application's actual default.
            startScenario(name, work, upstream, Map.of("SENDIUM_DLR_STORAGE_BACKEND", "memory",
                    "JAVA_TOOL_OPTIONS", "-Xms512m -Xmx512m -XX:+UseG1GC"));
            String baseline = memorySample(name, "baseline");
            require(metricSum(baseline, "sendium_dlr_storage_capacity_maximum", "backend=\"memory\"") == 100_000,
                    "Capacity qualification is not using the 100,000 default");
            try (var client = new DownstreamSmppClient()) {
                client.start();
                for (int start = 1; start <= 99_500; start += 1_000) {
                    int count = Math.min(1_000, 99_501 - start);
                    runBoundedSmppBatch(start, count, accepted -> {
                        try {
                            require(client.sendSms("capacity", "306900000001", "capacity-" + accepted).getCommandStatus()
                                    == SmppConstants.STATUS_OK, "Default capacity rejected an in-range SMPP submission");
                        } catch (Exception failure) {
                            throw new IllegalStateException("Capacity ingress batch failed", failure);
                        }
                    });
                    int accepted = start + count - 1;
                    require(upstream.awaitSubmitCount(first - 1 + accepted), "Capacity provider processing stalled");
                    awaitSmsDrain();
                    awaitCapacity(accepted);
                    if (accepted % 10_000 == 0 || accepted == 99_500) {
                        memorySample(name, "occupied-" + accepted);
                    }
                }
            }
            // 500 disconnected SMPP deliveries, 99,000 unresolved correlations.
            runBoundedSmppBatch(first, 500, upstream::sendDeliveryReceipt);
            callbacks.failAll = true;
            double retries = successfulStorageOperationCount(get("/q/metrics").body(), "retry_delivery");
            var httpIds = new ArrayList<String>();
            for (int i = 0; i < 500; i++) {
                var response = submitHttpRaw(callbacks, "capacity-http-" + i, true);
                require(response.statusCode() == 202, "Default capacity rejected an in-range HTTP submission");
                httpIds.add(response.body().trim());
            }
            require(upstream.awaitSubmitCount(first - 1 + 100_000), "HTTP capacity sources did not reach provider");
            awaitSmsDrain();
            runBoundedSmppBatch(first + 99_500, 500, upstream::sendDeliveryReceipt);
            require(callbacks.awaitGatewayIds(httpIds), "Capacity workload did not attempt every failed-first HTTP receipt");
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (successfulStorageOperationCount(get("/q/metrics").body(), "retry_delivery") < retries + 500
                    && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            require(successfulStorageOperationCount(get("/q/metrics").body(), "retry_delivery") >= retries + 500,
                    "Capacity workload did not establish real HTTP retry ownership");
            awaitCapacity(100_000);
            memorySample(name, "occupied-100000");
            require(submitHttpRaw(callbacks, "capacity-overload", true).statusCode() == 503,
                    "Full memory capacity did not reject HTTP overload");
            // A separate account must probe overload: binding the receipt owner would
            // immediately replay its pending deliveries and legitimately free capacity.
            try (var probe = new DownstreamSmppClient("capacity-probe")) {
                probe.start();
                require(probe.sendSms("sender", "306900000001", "capacity-overload-smpp").getCommandStatus()
                        == SmppConstants.STATUS_THROTTLED, "Full capacity did not throttle SMPP overload");
                require(probe.sendSms("sender", "306900000001", "capacity-non-dlr", false).getCommandStatus()
                        == SmppConstants.STATUS_OK, "Full DLR capacity blocked eligible non-DLR ingress");
            }
            try (var client = new DownstreamSmppClient()) {
                client.start();
                require(upstream.awaitSubmitCount(first + 100_000), "Full DLR capacity blocked non-DLR provider progress");
                require(upstream.awaitBodies(List.of("capacity-non-dlr")), "Non-DLR progress barrier was not submitted");
                callbacks.failAll = false;
                for (int start = first + 500; start < first + 99_500; start += 1_000) {
                    int count = Math.min(1_000, first + 99_500 - start);
                    runBoundedSmppBatch(start, count, upstream::sendDeliveryReceipt);
                    if ((start - first - 500 + count) % 10_000 == 0 || start + count == first + 99_500) {
                        memorySample(name, "draining-" + (start - first - 500 + count));
                    }
                }
                deadline = System.nanoTime() + TIMEOUT.toNanos();
                while (client.receivedDeliveries.get() < 99_500 && System.nanoTime() < deadline) {
                    Thread.sleep(100);
                }
                require(client.receivedDeliveries.get() == 99_500, "Capacity SMPP receipt drain lost or duplicated deliveries");
                awaitCapacity(0);
            }
            awaitSmsDrain();
            memorySample(name, "drained");
            System.out.println("memoryCapacityAtDefault|success|image:" + IMAGE
                    + "|accepted:100000|unresolved:99000|httpRetries:500|disconnectedSmpp:500"
                    + "|heapMiB:512|collector:G1|overload:rejected|nonDlr:progressed|finalUsed:0|result:PASS");
        } catch (Throwable failure) {
            System.out.println("memoryCapacityAtDefault|error|image:" + IMAGE + "|result:STOP");
            printDockerLogs(name);
            throw failure;
        } finally {
            callbacks.failAll = false;
            stopContainer(name);
        }
    }

    // Below the 1,000-PDU SMPP window; never queue the entire 100,000-source workload.
    // Every network acknowledgement and task failure is observed within one batch deadline.
    static void runBoundedSmppBatch(int first, int count, IntConsumer operation) throws Exception {
        require(count > 0 && count <= 1_000, "SMPP qualification batch must contain 1..1000 operations");
        var executor = Executors.newFixedThreadPool(100, Thread.ofVirtual().factory());
        try {
            var futures = new ArrayList<Future<?>>();
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            for (int i = first; i < first + count; i++) {
                int index = i;
                futures.add(executor.submit(() -> operation.accept(index)));
            }
            for (var future : futures) {
                future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
        } finally {
            executor.shutdownNow();
            require(executor.awaitTermination(35, TimeUnit.SECONDS), "SMPP qualification batch did not terminate");
        }
    }

    private static void awaitSmsDrain() throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        do {
            String metrics = get("/q/metrics").body();
            if (metricSum(metrics, "sendium_sms_pending_sources", "backend=\"memory\"") == 0
                    && metricSum(metrics, "sendium_sms_selected_work", "") == 0
                    && metricSum(metrics, "sendium_sms_routed_work", "") == 0) {
                return;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Capacity workload did not drain SMS stages");
    }

    private static String memorySample(String name, String phase) throws Exception {
        String metrics = get("/q/metrics").body();
        require(metricSum(metrics, "jvm_memory_max_bytes", "id=\"G1 Old Gen\"") == 512L * 1024 * 1024,
                "Capacity proof requires the unchanged 512 MiB G1 heap");
        double heap = metricSum(metrics, "jvm_memory_used_bytes", "area=\"heap\"");
        double gcCount = metrics.contains("jvm_gc_pause_seconds_count{") ? metricSum(metrics, "jvm_gc_pause_seconds_count", "") : 0;
        double gcSeconds = metrics.contains("jvm_gc_pause_seconds_sum{") ? metricSum(metrics, "jvm_gc_pause_seconds_sum", "") : 0;
        var process = new ProcessBuilder("docker", "exec", name, "cat", "/proc/1/status").start();
        String status = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        require(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0, "Cannot measure process RSS");
        String rss = status.lines().filter(line -> line.startsWith("VmRSS:")).findFirst().orElseThrow();
        System.out.println("memoryCapacitySample|success|phase:" + phase + "|heapUsedBytes:" + (long) heap
                + "|gcPauses:" + (long) gcCount + "|gcPauseSeconds:" + gcSeconds + "|rss:" + rss.replaceAll("\\s+", " "));
        System.out.println(metrics.lines().filter(line -> line.startsWith("jvm_info")).findFirst().orElseThrow());
        return metrics;
    }

    private static Map<String, String> recoverySettings(String backend, int capacity) {
        return Map.of("SENDIUM_DLR_STORAGE_BACKEND", backend,
                "SENDIUM_DLR_STORAGE_MEMORY_MAX_TRACKED_SUBMISSIONS", Integer.toString(capacity),
                "SENDIUM_MESSAGE_PENDING_BACKEND", "file",
                "SENDIUM_MESSAGE_ROUTER_QUEUE_BACKEND", "file",
                "SENDIUM_MESSAGE_ROUTED_WORK_BACKEND", "file",
                "SENDIUM_MESSAGE_SELECTION_BATCH_SIZE", "2");
    }

    private static void verifyRecoveredMemoryCapacity(UpstreamSmppServer upstream, CallbackServer callbacks) throws Exception {
        // Each subcase has its own retained SMS volume, never a retained DLR store.
        for (int limit : List.of(4, 2, 3)) {
            String name = "sendium-e2e-recovery-" + UUID.randomUUID().toString().substring(0, 8);
            Path work = Files.createTempDirectory("sendium-memory-recovery-");
            writeRuntimeConfig(work);
            Files.writeString(work.resolve("conf/smsg.properties"), "\noutSms.instance.route.pause = true\n", StandardOpenOption.APPEND);
            upstream.setAutomaticDelivery(false);
            int before = upstream.receivedSubmits.get();
            try {
                startScenario(name, work, upstream, recoverySettings("memory", 4));
                var sources = new ArrayList<String>();
                for (int i = 0; i < 2; i++) {
                    sources.add(submitHttp(callbacks, "recovered-" + limit + "-" + i, true).body().trim());
                }
                var recorded = awaitStageSources(name, "routed", sources);
                require(upstream.receivedSubmits.get() == before, "Paused recovery seed was submitted");
                require(stopContainer(name), "Cannot replace recovery seed");
                startScenario(name, work, upstream, recoverySettings("memory", limit));
                awaitCapacity(2);
                require(awaitStageSources(name, "routed", sources).equals(recorded), "Recovered assignment changed");
                var newRequest = submitHttpRaw(callbacks, "recovery-new-" + limit, true);
                require(newRequest.statusCode() == (limit == 2 ? 503 : 202),
                        "Unchanged/full/spare recovery admission did not reflect restored reservations");
                require(stopContainer(name), "Cannot unpause recovery");
                unpauseProvider(work);
                setRoutingGate(work, true);
                startScenario(name, work, upstream, recoverySettings("memory", limit));
                require(upstream.awaitSubmitCount(before + 2), "Recovered recorded destinations did not dispatch");
                require(upstream.awaitBodies(List.of("recovered-" + limit + "-0", "recovered-" + limit + "-1")),
                        "Recovery changed accepted payloads");
                System.out.println("memoryRecoveredSmsCapacity|success|image:" + IMAGE + "|case:"
                        + (limit == 4 ? "unchanged" : limit == 2 ? "exactlyFull" : "spare")
                        + "|acceptedReservations:2|recordedDestinations:true");
            } catch (Throwable failure) {
                printDockerLogs(name);
                throw failure;
            } finally {
                stopContainer(name);
                run(List.of("docker", "volume", "rm", name + "-pending"), true).waitFor(30, TimeUnit.SECONDS);
            }
        }
        verifyReducedRecoveryPriority(upstream, callbacks);
    }

    private static void verifyReducedRecoveryPriority(UpstreamSmppServer upstream, CallbackServer callbacks) throws Exception {
        String name = "sendium-e2e-reduced-" + UUID.randomUUID().toString().substring(0, 8);
        Path work = Files.createTempDirectory("sendium-memory-reduced-");
        writeRuntimeConfig(work);
        Files.writeString(work.resolve("conf/smsg.properties"), "\noutSms.instance.route.pause = true\n", StandardOpenOption.APPEND);
        upstream.setAutomaticDelivery(false);
        int before = upstream.receivedSubmits.get();
        try {
            startScenario(name, work, upstream, recoverySettings("memory", 6));
            var sources = new ArrayList<String>();
            for (int i = 0; i < 4; i++) {
                sources.add(submitHttp(callbacks, "reduced-" + i, true).body().trim());
            }
            awaitStageSources(name, "routed", sources);
            require(stopContainer(name), "Cannot replace reduced-limit seed");
            startScenario(name, work, upstream, recoverySettings("memory", 3));
            awaitCapacity(0);
            require(submitHttpRaw(callbacks, "reduced-unprotected-new", true).statusCode() == 503,
                    "New DLR ingress stole capacity from unprotected accepted work");
            try (var client = new DownstreamSmppClient()) {
                client.start();
                require(client.sendSms("sender", "306900000001", "reduced-unprotected-smpp").getCommandStatus()
                        == SmppConstants.STATUS_THROTTLED, "Reduced recovery did not throttle new SMPP DLR ingress");
            }
            require(stopContainer(name), "Cannot unpause reduced recovery");
            unpauseProvider(work);
            setRoutingGate(work, true);
            startScenario(name, work, upstream, recoverySettings("memory", 3));
            require(upstream.awaitSubmitCount(before + 3), "Reduced recovery did not dispatch protected work");
            awaitCapacity(3);
            require(submitHttpRaw(callbacks, "reduced-still-unprotected", true).statusCode() == 503,
                    "Reduced recovery admitted new DLR while old obligations remained unprotected");
            upstream.sendDeliveryReceipt(before + 1);
            upstream.sendDeliveryReceipt(before + 2);
            require(upstream.awaitSubmitCount(before + 4), "Freed capacity did not prioritize remaining accepted work");
            awaitCapacity(2);
            require(submitHttpRaw(callbacks, "reduced-protected-new", true).statusCode() == 202,
                    "Reservation priority incorrectly waited for all old receipts to drain");
            awaitCapacity(3);
            require(upstream.receivedSubmits.get() == before + 4, "New ingress bypassed the routing gate");
            System.out.println("memoryRecoveredSmsCapacity|success|image:" + IMAGE
                    + "|case:reduced|newIngressWhileUnprotected:rejected|spareAfterProtection:accepted|oldReceiptsStillPending:2");
        } catch (Throwable failure) {
            printDockerLogs(name);
            throw failure;
        } finally {
            stopContainer(name);
            run(List.of("docker", "volume", "rm", name + "-pending"), true).waitFor(30, TimeUnit.SECONDS);
        }
    }

    private static void verifyNonDlrBeyondBlockedWindow(UpstreamSmppServer upstream, CallbackServer callbacks) throws Exception {
        String name = "sendium-e2e-window-" + UUID.randomUUID().toString().substring(0, 8);
        Path work = Files.createTempDirectory("sendium-blocked-window-");
        writeRuntimeConfig(work);
        Files.writeString(work.resolve("conf/smsg.properties"),
                "\noutSms.instance.route.pause = true\noutSms.instance.smpp.reassembling.timeoutMillis = 1000\n",
                StandardOpenOption.APPEND);
        upstream.setAutomaticDelivery(false);
        int before = upstream.receivedSubmits.get();
        try {
            startScenario(name, work, upstream, recoverySettings("memory", 8));
            var blocked = new ArrayList<String>();
            // Incomplete multipart groups with three separately accepted duplicate parts each.
            // Reassembly at 1s makes two atomic three-slot groups; neither fits a reduced limit of two.
            try (var client = new DownstreamSmppClient()) {
                client.start();
                for (int reference = 1; reference <= 2; reference++) {
                    for (int duplicate = 0; duplicate < 3; duplicate++) {
                        var response = client.sendPart(1, "blocked-" + reference, reference);
                        require(response.getCommandStatus() == SmppConstants.STATUS_OK, "Blocked group seed failed");
                        blocked.add(response.getMessageId());
                    }
                }
            }
            awaitStageSources(name, "routed", blocked);
            String eligible = submitHttpRaw(callbacks, "beyond-window-non-dlr-" + DLR_BACKEND, false).body().trim();
            var all = new ArrayList<>(blocked);
            all.add(eligible);
            Map<String, String> recorded = awaitStageSources(name, "routed", all);
            require(stopContainer(name), "Cannot replace blocked-window seed");
            unpauseProvider(work);
            setRoutingGate(work, true);
            startScenario(name, work, upstream, recoverySettings(DLR_BACKEND, 2));
            require(upstream.awaitSubmitCount(before + 1), "Eligible work beyond blocked prefetch window did not progress");
            require(upstream.receivedSubmits.get() == before + 1
                            && upstream.receivedBodies.contains("beyond-window-non-dlr-" + DLR_BACKEND),
                    "Blocked DLR group reached provider");
            for (String id : blocked) {
                require(sourceExists(name, id), "Blocked accepted source lost durable ownership");
            }
            var retained = awaitStageSources(name, "routed", blocked);
            for (String id : blocked) {
                require(recorded.get(id).equals(retained.get(id)), "Blocked group's recorded assignment changed");
            }
            require(retained.values().stream().distinct().count() == 2, "Blocked multipart groups were split");
            String diagnosis = readDockerLogs(name);
            if (MEMORY_DLR) {
                awaitCapacity(0);
                require(diagnosis.contains("reason:MAXIMUM_TOO_SMALL|requiredSources:3|maxTrackedSubmissions:2|action:increaseMaxTrackedSubmissions"),
                        "Oversized recovered group lacks an actionable capacity diagnosis");
            } else {
                require(diagnosis.contains("reason:disabled|action:enableDlrHandling"),
                        "Retained disabled work lacks an actionable diagnosis");
            }
            System.out.println("nonDlrBeyondBlockedWindow|success|image:" + IMAGE + "|backend:" + DLR_BACKEND
                    + "|prefetch:2|blockedGroups:2|retainedSources:6|recordedDestination:true");
        } catch (Throwable failure) {
            printDockerLogs(name);
            throw failure;
        } finally {
            stopContainer(name);
            run(List.of("docker", "volume", "rm", name + "-pending"), true).waitFor(30, TimeUnit.SECONDS);
        }
    }

    private static void unpauseProvider(Path work) throws IOException {
        Path properties = work.resolve("conf/smsg.properties");
        Files.writeString(properties, Files.readString(properties).replace("outSms.instance.route.pause = true",
                "outSms.instance.route.pause = false"));
    }

    private static Process startScenario(String name, Path work, UpstreamSmppServer upstream,
                                         Map<String, String> settings) throws Exception {
        int sessions = upstream.boundSessionCount();
        Process container = startSendiumContainer(name, work, settings);
        waitForDlrReadiness(settings.getOrDefault("SENDIUM_DLR_STORAGE_BACKEND", DLR_BACKEND));
        waitForPort("localhost", SENDIUM_SMPP_PORT, TIMEOUT);
        require(upstream.awaitSessionBoundAfter(sessions), "Scenario did not bind provider");
        return container;
    }

    private static void verifyMemoryHttpRetry(UpstreamSmppServer upstream, CallbackServer callbacks) throws Exception {
        upstream.setAutomaticDelivery(false);
        int submit = upstream.receivedSubmits.get() + 1;
        String gateway = submitHttp(callbacks, "memory-http-retry", true).body().trim();
        require(upstream.awaitSubmitCount(submit), "Memory HTTP message did not reach provider");
        awaitSuccessfulStorageOperation("provider_batch", 0);
        upstream.sendDeliveryReceipt(submit);
        require(callbacks.awaitCallbacks(1), "Failed-first HTTP callback was not attempted");
        awaitSuccessfulStorageOperation("retry_delivery", 0);
        awaitCapacity(1);
        require(callbacks.awaitCallbacks(2), "Memory HTTP callback was not retried");
        require(callbacks.query(0).contains("id=" + gateway) && callbacks.query(1).equals(callbacks.query(0)),
                "Memory retry changed the receipt identity");
        require(callbacks.attemptNanos.get(1) - callbacks.attemptNanos.get(0) >= Duration.ofSeconds(5).toNanos(),
                "Memory HTTP retry ignored configured five-second timing");
        awaitSuccessfulStorageOperation("complete_delivery", 0);
        awaitCapacity(0);
        System.out.println("memoryHttpRetry|success|image:" + IMAGE + "|attempts:2|configuredRetry:5S|capacityReleased:true");
    }

    private static void verifyMemorySmppReconnect(UpstreamSmppServer upstream) throws Exception {
        int submit = upstream.receivedSubmits.get() + 1;
        double batches = successfulStorageOperationCount(get("/q/metrics").body(), "provider_batch");
        String gateway;
        try (var client = new DownstreamSmppClient()) {
            client.start();
            var response = client.sendSms("smpp-sender", "306900000001", "memory-smpp-reconnect");
            require(response.getCommandStatus() == SmppConstants.STATUS_OK, "Memory SMPP admission failed");
            gateway = response.getMessageId();
            require(upstream.awaitSubmitCount(submit), "Memory SMPP message did not reach provider");
            awaitSuccessfulStorageOperation("provider_batch", batches);
        }
        upstream.sendDeliveryReceipt(submit);
        awaitCapacity(1);
        double completions = successfulStorageOperationCount(get("/q/metrics").body(), "complete_delivery");
        try (var client = new DownstreamSmppClient()) {
            client.start();
            var receipt = client.awaitDeliverSm();
            require(receipt != null, "Disconnected memory SMPP receipt was not delivered on rebind");
            String body = new String(receipt.getShortMessage(), StandardCharsets.UTF_8);
            require(body.contains("id:" + gateway) && body.contains("DELIVRD"), "Wrong reconnected memory receipt");
            awaitSuccessfulStorageOperation("complete_delivery", completions);
        }
        awaitCapacity(0);
        System.out.println("memorySmppReconnect|success|image:" + IMAGE + "|disconnectedReceipt:true|capacityReleased:true");
    }

    private static Process verifyMemoryLossOnReplacement(String name, Path workDir, UpstreamSmppServer upstream,
                                                         CallbackServer callbacks) throws Exception {
        int submit = upstream.receivedSubmits.get() + 1;
        double batches = successfulStorageOperationCount(get("/q/metrics").body(), "provider_batch");
        submitHttp(callbacks, "memory-lost-correlation", true);
        require(upstream.awaitSubmitCount(submit), "Lost-correlation source did not reach provider");
        awaitSuccessfulStorageOperation("provider_batch", batches);
        // Keep a disconnected downstream delivery as well as an unresolved provider correlation.
        int smppSubmit = submit + 1;
        try (var client = new DownstreamSmppClient()) {
            client.start();
            require(client.sendSms("smpp-sender", "306900000001", "memory-lost-pending").getCommandStatus()
                    == SmppConstants.STATUS_OK, "Lost-pending SMPP admission failed");
            require(upstream.awaitSubmitCount(smppSubmit), "Lost-pending source did not reach provider");
            awaitSuccessfulStorageOperation("provider_batch", batches + 1);
        }
        upstream.sendDeliveryReceipt(smppSubmit);
        awaitCapacity(2);
        int callbacksBefore = callbacks.count();
        require(stopContainer(name), "Cannot replace memory process");
        Process replacement = startReplacement(name, workDir, upstream);
        awaitCapacity(0);
        upstream.sendDeliveryReceipt(submit);
        awaitSuccessfulStorageOperation("provider_batch", 0);
        String output = readDockerLogs(name);
        require(output.contains("receiveDlr|unresolved|reason:unknown_or_expired_provider_message|providerMessageId:e2e-" + submit),
                "Old provider receipt was not demonstrably unmatched");
        // A fresh receipt provides an observable replay/dispatch barrier, rather than a sleep-only negative check.
        try (var client = new DownstreamSmppClient()) {
            client.start();
            verifySmppSubmitGetsDeliverSmWithAutomaticReceipt(upstream, client);
            require(client.receivedDeliveries.get() == 1, "Previous pending SMPP delivery was replayed after replacement");
        }
        require(callbacks.count() == callbacksBefore, "Previous memory HTTP state replayed after replacement");
        awaitCapacity(0);
        System.out.println("memoryDlrLossOnReplacement|success|image:" + IMAGE + "|oldReceipt:unmatched|pendingReplay:0");
        return replacement;
    }

    private static void verifySmppSubmitGetsDeliverSmWithAutomaticReceipt(UpstreamSmppServer upstream,
                                                                         DownstreamSmppClient client) throws Exception {
        upstream.setAutomaticDelivery(true);
        var response = client.sendSms("smpp-sender", "306900000001", "replacement-barrier");
        require(response.getCommandStatus() == SmppConstants.STATUS_OK, "Replacement barrier admission failed");
        var receipt = client.awaitDeliverSm();
        require(receipt != null && new String(receipt.getShortMessage(), StandardCharsets.UTF_8)
                .contains("id:" + response.getMessageId()), "Replacement barrier receipt failed");
    }

    private static void verifyDisabledIngress(UpstreamSmppServer upstream, CallbackServer callbacks) throws Exception {
        int before = upstream.receivedSubmits.get();
        var kannel = submitHttp(callbacks, "disabled-kannel", true);
        require(kannel.statusCode() == 400, "Disabled Kannel DLR request was not rejected");
        HttpRequest nativeRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + SENDIUM_HTTP_PORT + "/api/v1/messages"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer smoke-native-key")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"from\":\"sender\",\"to\":\"306900000002\",\"text\":\"disabled-native\","
                        + "\"callbackUrl\":\"http://host.docker.internal:" + callbacks.port() + "/dlr\"}")).build();
        var nativeResponse = HTTP.send(nativeRequest, HttpResponse.BodyHandlers.ofString());
        require(nativeResponse.statusCode() == 400, "Disabled native DLR request was not rejected");
        var error = new ObjectMapper().readTree(nativeResponse.body());
        require(error.path("code").asText().equals("INVALID_REQUEST")
                        && error.path("message").asText().equals("Unsupported message submission"),
                "Disabled native request failed for a reason other than selected DLR policy");
        try (var client = new DownstreamSmppClient()) {
            client.start();
            require(client.sendSms("smpp-sender", "306900000001", "disabled-smpp").getCommandStatus()
                    == SmppConstants.STATUS_SUBMITFAIL, "Disabled SMPP DLR request was not rejected");
        }
        submitHttp(callbacks, "disabled-non-dlr-barrier", false);
        require(upstream.awaitSubmitCount(before + 1), "Disabled mode blocked non-DLR traffic");
        require(upstream.receivedSubmits.get() == before + 1
                        && upstream.receivedBodies.contains("disabled-non-dlr-barrier"),
                "Rejected disabled-mode traffic reached the provider");
        require(callbacks.count() == 0, "Disabled mode generated callbacks");
        System.out.println("disabledDlrIngressRejected|success|image:" + IMAGE + "|http:400|smpp:STATUS_SUBMITFAIL|nonDlr:delivered");
    }

    private static HttpResponse<String> submitHttp(CallbackServer callbacks, String text, boolean dlr) throws Exception {
        var response = submitHttpRaw(callbacks, text, dlr);
        if (!DISABLED_DLR || !dlr) {
            require(response.statusCode() == 202, "HTTP admission failed: " + response.statusCode());
        }
        return response;
    }

    private static HttpResponse<String> submitHttpRaw(CallbackServer callbacks, String text, boolean dlr) throws Exception {
        String url = "/sendsms?username=http-user&password=http-pass&from=http-sender&to=306900000002&text="
                + URLEncoder.encode(text, StandardCharsets.UTF_8);
        if (dlr) {
            url += "&dlr-mask=3&dlr-url=" + URLEncoder.encode(
                    "http://host.docker.internal:" + callbacks.port() + "/dlr?status=%d&id=%I", StandardCharsets.UTF_8);
        }
        return get(url);
    }

    private static void awaitCapacity(long expected) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        do {
            String metrics = get("/q/metrics").body();
            if (metricSum(metrics, "sendium_dlr_storage_capacity_used", "backend=\"memory\"") == expected) {
                return;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Memory capacity did not reach " + expected);
    }

    static double metricSum(String metrics, String name, String labels) {
        var values = metrics.lines().filter(line -> line.startsWith(name + "{") || line.startsWith(name + " "))
                .filter(line -> line.contains(labels)).map(line -> line.substring(line.lastIndexOf(' ') + 1))
                .mapToDouble(Double::parseDouble).toArray();
        require(values.length > 0, "Missing metric: " + name);
        require(java.util.Arrays.stream(values).allMatch(value -> Double.isFinite(value) && value >= 0),
                "Invalid metric: " + name);
        double sum = java.util.Arrays.stream(values).sum();
        require(Double.isFinite(sum) && sum >= 0, "Invalid metric: " + name);
        return sum;
    }

    private static Process verifyFileHttpRetrySurvivesReplacement(String name, Path workDir, UpstreamSmppServer upstream,
                                                                  CallbackServer callbacks, Path helper,
                                                                  String volume) throws Exception {
        upstream.setAutomaticDelivery(false);
        try {
            String dlrUrl = "http://host.docker.internal:" + callbacks.port() + "/dlr?status=%d&id=%I";
            double batches = successfulStorageOperationCount(get("/q/metrics").body(), "provider_batch");
            var response = get("/sendsms?username=http-user&password=http-pass&dlr-mask=3&from=http-sender&to=306900000002" +
                    "&text=" + URLEncoder.encode("file dlr http retry", StandardCharsets.UTF_8) +
                    "&dlr-url=" + URLEncoder.encode(dlrUrl, StandardCharsets.UTF_8));
            require(response.statusCode() == HttpURLConnection.HTTP_ACCEPTED, "File-mode HTTP admission failed: " + response.statusCode());
            String gatewayId = response.body().trim();
            require(upstream.awaitSubmitCount(1), "Upstream did not receive the file-mode HTTP message");
            // The provider receipt is withheld until the acceptance and its correlation are committed.
            awaitSuccessfulStorageOperation("provider_batch", batches);

            require(stopContainer(name), "Cannot forcibly remove the container holding the correlation");
            startReplacement(name, workDir, upstream);
            upstream.sendDeliveryReceipt(1);
            require(callbacks.awaitCallbacks(1), "First HTTP DLR callback was not attempted after replacement");
            awaitSuccessfulStorageOperation("retry_delivery", 0);

            require(stopContainer(name), "Cannot forcibly remove the container holding the HTTP retry");
            List<String> rows = inspectDlr(helper, volume);
            require(rows.size() == 1 && rows.getFirst().startsWith(gatewayId + "|HTTP|PENDING|1|") &&
                    !rows.getFirst().endsWith("|-"), "Persisted HTTP retry was not retained: " + rows);
            // The retry interval is fixed at one hour; move only the stored due time to simulate it elapsing.
            var due = FileDlrRestartSupport.runJava(helper, List.of("-v", volume + ":/work/dlr"),
                    FileDlrRestartSupport.class.getName(), "makeDue", "/work/dlr");
            require(due.exitCode() == 0 && due.output().contains("madeDue:1"), "Cannot advance the stored retry: " + due.output());
            startReplacement(name, workDir, upstream);
            require(callbacks.awaitCallbacks(2), "Persisted HTTP retry was not delivered after replacement");
            String retried = callbacks.query(1);
            require(retried.contains("status=1") && retried.contains("id=" + gatewayId), "Unexpected retried callback: " + retried);
            awaitSuccessfulStorageOperation("complete_delivery", 0);

            require(stopContainer(name), "Cannot stop the container after HTTP completion");
            require(inspectDlr(helper, volume).isEmpty(), "Completed HTTP receipt remained in file storage");
            Process container = startReplacement(name, workDir, upstream);
            System.out.println("fileDlrHttpRetry|success|image:" + IMAGE + "|backend:file|replacements:3" +
                    "|callbackAttempts:" + callbacks.count() + "|retainedVolume:true");
            return container;
        } finally {
            upstream.setAutomaticDelivery(true);
        }
    }

    private static Process verifyFileSmppReplaySurvivesReplacement(String name, Path workDir, UpstreamSmppServer upstream,
                                                                   Path helper, String volume) throws Exception {
        upstream.setAutomaticDelivery(false);
        try {
            String gatewayId;
            double batches = successfulStorageOperationCount(get("/q/metrics").body(), "provider_batch");
            try (var client = new DownstreamSmppClient()) {
                client.start();
                var response = client.sendSms("smpp-sender", "306900000001", "file dlr smpp replay");
                require(response.getCommandStatus() == SmppConstants.STATUS_OK, "File-mode SMPP admission failed");
                gatewayId = response.getMessageId();
                require(upstream.awaitSubmitCount(2), "Upstream did not receive the file-mode SMPP message");
                awaitSuccessfulStorageOperation("provider_batch", batches);
            }
            // The client is now unbound; the provider acknowledgement follows durable resolution.
            upstream.sendDeliveryReceipt(2);

            require(stopContainer(name), "Cannot forcibly remove the container holding the SMPP receipt");
            List<String> rows = inspectDlr(helper, volume);
            require(rows.equals(List.of(gatewayId + "|SMPP|PENDING|0|-")), "Unbound SMPP receipt was not retained: " + rows);
            startReplacement(name, workDir, upstream);
            try (var client = new DownstreamSmppClient()) {
                client.start();
                DeliverSm replayed = client.awaitDeliverSm();
                require(replayed != null, "Retained SMPP receipt was not replayed on bind");
                String body = new String(replayed.getShortMessage(), StandardCharsets.UTF_8);
                require(body.contains("id:" + gatewayId) && body.contains("DELIVRD"), "Unexpected replayed receipt: " + body);
                awaitSuccessfulStorageOperation("complete_delivery", 0);
            }
            require(stopContainer(name), "Cannot stop the container after SMPP acknowledgement");
            require(inspectDlr(helper, volume).isEmpty(), "Acknowledged SMPP receipt remained in file storage");
            Process container = startReplacement(name, workDir, upstream);
            System.out.println("fileDlrSmppReplay|success|image:" + IMAGE + "|backend:file|replacements:2" +
                    "|unboundAcrossReplacement:true|retainedVolume:true");
            return container;
        } finally {
            upstream.setAutomaticDelivery(true);
        }
    }

    private static List<String> inspectDlr(Path helper, String volume) throws Exception {
        var result = FileDlrRestartSupport.runJava(helper, List.of("-v", volume + ":/work/dlr"),
                FileDlrRestartSupport.class.getName(), "inspect", "/work/dlr");
        require(result.exitCode() == 0, "Cannot inspect retained DLR volume: " + result.output());
        return result.output().lines().filter(line -> line.startsWith("row:")).map(line -> line.substring(4)).toList();
    }

    private static void verifySmppSubmitGetsDeliverSm(UpstreamSmppServer upstream, int expectedSubmitCount) throws Exception {
        try (DownstreamSmppClient client = new DownstreamSmppClient()) {
            client.start();
            SubmitSmResp response = client.sendSms("smpp-sender", "306900000001", "container smpp e2e");
            require(response.getCommandStatus() == SmppConstants.STATUS_OK,
                    "SMPP submit_sm_resp status was " + response.getCommandStatus());
            require(response.getMessageId() != null && !response.getMessageId().isBlank(),
                    "SMPP submit_sm_resp did not contain a message id");
            require(upstream.awaitSubmitCount(expectedSubmitCount), "Upstream SMPP server did not receive the SMPP-originated message");
            DeliverSm deliverSm = client.awaitDeliverSm();
            require(deliverSm != null, "Downstream SMPP client did not receive deliver_sm");
            String body = new String(deliverSm.getShortMessage(), StandardCharsets.UTF_8);
            require(body.contains("DELIVRD"), "Downstream deliver_sm was not delivered: " + body);
            require(body.contains("id:" + response.getMessageId()),
                    "Downstream deliver_sm did not contain gateway id " + response.getMessageId() + ": " + body);
            Thread.sleep(500);
        }
    }

    private static Process verifyHttpCorrelationSurvivesRestart(String containerName, Path workDir,
                                                                 UpstreamSmppServer upstream,
                                                                 CallbackServer callbackServer,
                                                                 int expectedSubmitCount) throws Exception {
        upstream.setAutomaticDelivery(false);
        String dlrUrl = "http://host.docker.internal:" + callbackServer.port() + "/dlr?status=%d&id=%I";
        String query = "username=http-user"
                + "&password=http-pass&dlr-mask=3"
                + "&from=http-sender"
                + "&to=306900000002"
                + "&text=" + URLEncoder.encode("container restart http e2e", StandardCharsets.UTF_8)
                + "&dlr-url=" + URLEncoder.encode(dlrUrl, StandardCharsets.UTF_8);
        try {
            HttpResponse<String> metricsBefore = get("/q/metrics");
            require(metricsBefore.statusCode() == HttpURLConnection.HTTP_OK, "Cannot read storage metrics before submission");
            double committedBatchesBefore = successfulStorageOperationCount(metricsBefore.body(), "provider_batch");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + SENDIUM_HTTP_PORT + "/sendsms?" + query))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            require(response.statusCode() == HttpURLConnection.HTTP_ACCEPTED,
                    "HTTP /sendsms returned " + response.statusCode() + ": " + response.body());

            String gatewayId = response.body().trim();
            require(!gatewayId.isBlank(), "HTTP /sendsms did not return a gateway message id");
            require(upstream.awaitSubmitCount(expectedSubmitCount),
                    "Upstream SMPP server did not receive the HTTP-originated message");
            awaitSuccessfulStorageOperation("provider_batch", committedBatchesBefore);

            int boundSessionsBeforeRestart = upstream.boundSessionCount();
            require(stopContainer(containerName), "Failed to remove Sendium container before restart");
            Process restartedContainer = startSendiumContainer(containerName, workDir);
            waitForDlrReadiness();
            waitForPort("localhost", SENDIUM_SMPP_PORT, TIMEOUT);
            require(upstream.awaitSessionBoundAfter(boundSessionsBeforeRestart),
                    "Sendium container did not rebind before the HTTP DLR replay");

            upstream.sendDeliveryReceipt(expectedSubmitCount);
            String callbackQuery = callbackServer.awaitCallback();
            require(callbackQuery != null, "DLR callback URL was not called after restart");
            require(callbackQuery.contains("status=1"), "DLR callback did not contain delivered status: " + callbackQuery);
            require(callbackQuery.contains("id=" + gatewayId),
                    "DLR callback did not contain gateway id " + gatewayId + ": " + callbackQuery);
            return restartedContainer;
        } finally {
            upstream.setAutomaticDelivery(true);
        }
    }

    private static Process startSendiumContainer(String containerName, Path workDir) throws Exception {
        return startSendiumContainer(containerName, workDir, Map.of());
    }

    private static Process startSendiumContainer(String containerName, Path workDir, Map<String, String> settings) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "--rm", "-d",
                "--name", containerName,
                "--add-host", "host.docker.internal:host-gateway",
                "-p", SENDIUM_HTTP_PORT + ":8080",
                "-p", SENDIUM_SMPP_PORT + ":27777",
                "-v", workDir.resolve("conf").toAbsolutePath() + ":/work/conf",
                "-v", workDir.resolve("logs").toAbsolutePath() + ":/work/logs",
                "-e", "QUARKUS_LOG_LEVEL=INFO",
                "-e", "LOG_LEVEL=INFO"
        ));
        if (FILE_DLR) {
            // No PostgreSQL settings at all: file mode must not need them.
            command.addAll(List.of("-v", containerName + "-dlr:/work/dlr",
                    "-e", "SENDIUM_DLR_STORAGE_BACKEND=file", "-e", "SENDIUM_DLR_STORAGE_FILE_PATH=/work/dlr"));
        } else if (MEMORY_DLR || DISABLED_DLR) {
            command.addAll(List.of("-e", "SENDIUM_DLR_STORAGE_BACKEND=" + DLR_BACKEND,
                    "-e", "SENDIUM_DLR_HTTP_RETRY_INTERVAL=5S",
                    "-e", "JAVA_TOOL_OPTIONS=-Xms512m -Xmx512m -XX:+UseG1GC"));
        } else {
            command.addAll(List.of("-e", "SENDIUM_DLR_POSTGRESQL_JDBC_URL=" + POSTGRESQL_JDBC_URL,
                    "-e", "SENDIUM_DLR_POSTGRESQL_USERNAME=" + POSTGRESQL_USERNAME,
                    "-e", "SENDIUM_DLR_POSTGRESQL_PASSWORD=" + POSTGRESQL_PASSWORD));
        }
        if (PENDING_RESTART) {
            command.addAll(List.of("-v", containerName + "-pending:/work/spool",
                    "-e", "SENDIUM_MESSAGE_PENDING_BACKEND=file", "-e", "SENDIUM_MESSAGE_PENDING_PATH=/work/spool"));
            String[] stages = STORAGE_PROFILE.split("/");
            command.addAll(List.of("-e", "SENDIUM_MESSAGE_ROUTER_QUEUE_BACKEND=" + stages[1],
                    "-e", "SENDIUM_MESSAGE_ROUTED_WORK_BACKEND=" + stages[2]));
        }
        if ("file".equals(settings.get("SENDIUM_MESSAGE_PENDING_BACKEND"))) {
            command.addAll(List.of("-v", containerName + "-pending:/work/spool",
                    "-e", "SENDIUM_MESSAGE_PENDING_PATH=/work/spool"));
        }
        settings.forEach((key, value) -> command.addAll(List.of("-e", key + "=" + value)));
        command.add(IMAGE);
        Process process = run(command, true);
        require(process.waitFor(30, TimeUnit.SECONDS), "Timed out starting Sendium container");
        require(process.exitValue() == 0, "Failed to start Sendium container");
        return process;
    }

    private static Process verifyPendingSurvivesReplacement(String name, Path workDir, UpstreamSmppServer upstream,
                                                            CallbackServer callbackServer) throws Exception {
        // Deliver receipts only after replacements and required correlation handoffs have finished.
        upstream.setAutomaticDelivery(false);
        var accepted = new ArrayList<String>();
        Map<String, String> stageBefore = Map.of();
        String namespace = STORAGE_PROFILE.endsWith("file") ? "routed" : "selected";
        try (var client = new DownstreamSmppClient()) {
            client.start();
            String callback = URLEncoder.encode("http://host.docker.internal:" + callbackServer.port() + "/dlr?id=%I", StandardCharsets.UTF_8);
            var http = get("/sendsms?username=http-user&password=http-pass&from=http-sender&to=306900000002&text=pending-http" +
                    "&dlr-mask=3&dlr-url=" + callback);
            require(http.statusCode() == 202, "Pending HTTP admission failed: " + http.statusCode());
            accepted.add(http.body().trim());
            var ordinary = client.sendSms("smpp-sender", "306900000001", "pending-smpp");
            require(ordinary.getCommandStatus() == SmppConstants.STATUS_OK, "Pending SMPP admission failed");
            accepted.add(ordinary.getMessageId());
            for (int duplicate = 0; duplicate < 2; duplicate++) {
                var part = client.sendPart(1, "pending-");
                require(part.getCommandStatus() == SmppConstants.STATUS_OK, "Pending multipart admission failed");
                accepted.add(part.getMessageId());
            }
            for (String id : accepted) {
                require(sourceExists(name, id), "Acknowledged source was not committed: " + id);
            }
            if (!STORAGE_PROFILE.equals("file/memory/memory")) {
                stageBefore = awaitStageSources(name, namespace, accepted.subList(0, 2));
            }
            require(upstream.receivedSubmits.get() == 0, "Paused application submitted work before replacement");
        }
        require(stopContainer(name), "Cannot forcibly remove original pending container");
        Path properties = workDir.resolve("conf/smsg.properties");
        Files.writeString(properties, Files.readString(properties).replace("outSms.pause = true", "outSms.pause = false"));
        if (STORAGE_PROFILE.endsWith("file")) {
            // An accidental reroute now finds no destination; only the committed assignment can dispatch.
            setRoutingGate(workDir, true);
        }
        Process restarted = startReplacement(name, workDir, upstream);
        require(get("/q/health/ready").body().contains("sendium-sms-storage"), "Replacement lacks storage readiness check");
        if (!STORAGE_PROFILE.equals("file/memory/memory")) {
            Map<String, String> stageAfter = awaitStageSources(name, namespace, accepted.subList(0, 2));
            require(stageAfter.equals(stageBefore), "Replacement changed committed stage identities");
            require(upstream.receivedSubmits.get() == 0, "Stage gate allowed provider submission before identity inspection");
            if (STORAGE_PROFILE.endsWith("file")) {
                require(stopContainer(name), "Cannot replace paused stage-inspection container");
                Files.writeString(properties, Files.readString(properties)
                        .replace("outSms.instance.route.pause = true", "outSms.instance.route.pause = false"));
                restarted = startReplacement(name, workDir, upstream);
                require(upstream.awaitSubmitCount(2), "Recorded destinations did not dispatch while rerouting was gated");
                long cleanupDeadline = System.nanoTime() + TIMEOUT.toNanos();
                for (String id : accepted.subList(0, 2)) {
                    while (sourceExists(name, id) && System.nanoTime() < cleanupDeadline) {
                        Thread.sleep(100);
                    }
                    require(!sourceExists(name, id), "Recorded assignment did not finish its required handoff");
                }
            }
            // Load configuration at replacement rather than relying on bind-mount file-watch events.
            require(stopContainer(name), "Cannot replace routing-gated container");
            setRoutingGate(workDir, false);
            restarted = startReplacement(name, workDir, upstream);
        }
        try (var client = new DownstreamSmppClient()) {
            client.start();
            var last = client.sendPart(2, "multipart");
            require(last.getCommandStatus() == SmppConstants.STATUS_OK, "Replacement rejected recovered multipart group");
            accepted.add(last.getMessageId());
            require(upstream.awaitSubmitCount(3), "Provider did not receive recovered HTTP/SMPP/multipart messages");
            require(upstream.receivedBodies.containsAll(List.of("pending-http", "pending-smpp", "pending-multipart")),
                    "Recovered provider payloads do not match acknowledged content");
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            for (String id : accepted) {
                while (sourceExists(name, id) && System.nanoTime() < deadline) {
                    Thread.sleep(100);
                }
                require(!sourceExists(name, id), "Terminal source was not removed: " + id);
            }
            require(upstream.receivedSubmits.get() == 3, "Duplicate ordinal produced extra provider submission");
            for (int submit = 1; submit <= 3; submit++) {
                upstream.sendDeliveryReceipt(submit);
            }
            String callback = callbackServer.awaitCallback();
            require(callback != null && callback.contains("id=" + accepted.getFirst()), "Recovered HTTP DLR callback was not delivered");
            while ((!upstream.pendingDeliveryReceipts.isEmpty() || client.receivedDeliveries.get() < 3) && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            require(upstream.pendingDeliveryReceipts.isEmpty(), "Provider DLR acknowledgements did not finish before teardown");
            require(client.receivedDeliveries.get() >= 3, "Recovered ordinary/multipart SMPP DLRs were not delivered");
        }
        System.out.println("pendingRestart|success|image:" + IMAGE + "|profile:" + STORAGE_PROFILE +
                "|revision:" + System.getenv().getOrDefault("SENDIUM_E2E_REVISION", "unknown") +
                "|acknowledgedSources:5|providerMessages:3|retainedVolume:true|stageIdentityProbe:" + !stageBefore.isEmpty() +
                "|rerouteGateProbe:" + STORAGE_PROFILE.endsWith("file"));
        return restarted;
    }

    private static Process startReplacement(String name, Path workDir, UpstreamSmppServer upstream) throws Exception {
        int sessions = upstream.boundSessionCount();
        Process container = startSendiumContainer(name, workDir);
        waitForDlrReadiness();
        waitForPort("localhost", SENDIUM_SMPP_PORT, TIMEOUT);
        require(upstream.awaitSessionBoundAfter(sessions), "Replacement did not bind to provider");
        return container;
    }

    private static void setRoutingGate(Path workDir, boolean gated) throws IOException {
        Path table = workDir.resolve("conf/routingTable.conf");
        String content = Files.readString(table);
        content = gated ? content.replace("route::default:", "unconfigured-stage-gate::default:") :
                content.replace("unconfigured-stage-gate::default:", "route::default:");
        Files.writeString(table, content);
    }

    private static Map<String, String> awaitStageSources(String container, String namespace, List<String> sources) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        Map<String, String> identities;
        do {
            identities = stageSources(container, namespace);
            if (identities.keySet().containsAll(sources)) {
                return identities;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("stageRestart|error|reason:stageCommitTimeout|stage:" + namespace);
    }

    private static Map<String, String> stageSources(String container, String namespace) throws Exception {
        var identities = new TreeMap<String, String>();
        var listing = new ProcessBuilder("docker", "exec", container, "sh", "-c",
                "for f in /work/spool/\"$1\"/*/*.cbor; do if test -f \"$f\"; then printf '%s\\n' \"$f\"; fi; done",
                "sh", namespace).start();
        String paths = new String(listing.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        require(listing.waitFor(10, TimeUnit.SECONDS) && listing.exitValue() == 0, "Cannot inspect stage records");
        var mapper = new ObjectMapper(new CBORFactory());
        for (String path : paths.lines().toList()) {
            var read = new ProcessBuilder("docker", "exec", container, "cat", path).start();
            byte[] bytes = read.getInputStream().readAllBytes();
            require(read.waitFor(10, TimeUnit.SECONDS) && read.exitValue() == 0, "Cannot read stage record");
            var node = mapper.readTree(bytes);
            String identity = node.required(namespace.equals("routed") ? "workId" : "selectionId").textValue();
            for (var source : node.required("sources")) {
                require(identities.put(source.textValue(), identity) == null, "Conflicting stage identity probe");
            }
        }
        return identities;
    }

    private static boolean sourceExists(String container, String gatewayId) throws Exception {
        UUID id = UUID.fromString(gatewayId);
        Process check = run(List.of("docker", "exec", container, "sh", "-c", "test -s \"$1\"", "sh",
                "/work/spool/accepted/2/" + id.toString().substring(0, 2) + "/" + id + ".cbor"), false);
        require(check.waitFor(10, TimeUnit.SECONDS), "Timed out checking retained source");
        require(check.exitValue() == 0 || check.exitValue() == 1, "Cannot inspect retained spool");
        return check.exitValue() == 0;
    }

    private static void writeRuntimeConfig(Path workDir) throws IOException {
        Path conf = workDir.resolve("conf");
        Files.createDirectories(conf);
        Files.createDirectories(workDir.resolve("logs"));

        Files.writeString(conf.resolve("credentials.yml"), """
                credentials:
                  - type: SMPP
                    accountId: "smpp-e2e"
                    systemId: "smpp-user"
                    password: "smpp-pass"
                  - type: SMPP
                    accountId: "capacity-probe"
                    systemId: "capacity-probe"
                    password: "smpp-pass"
                  - type: HTTP
                    accountId: "http-e2e"
                    systemId: "http-user"
                    password: "http-pass"
                  - type: HTTP
                    accountId: "native-e2e"
                    apiKey: "smoke-native-key"
                """, StandardCharsets.UTF_8);

        Files.writeString(conf.resolve("smsg.properties"), """
                outSms.instance.route.enable = true
                outSms.instance.route.type = smppclient
                outSms.instance.route.username = upstream-user
                outSms.instance.route.password = upstream-pass
                outSms.instance.route.host = host.docker.internal
                outSms.instance.route.port = 27779
                outSms.instance.route.tps = 0
                outSms.instance.route.connections.transceivers = 1
                outSms.instance.route.connection.healthcheck = true
                outSms.instance.route.print.msgs = false

                outSms.instance.smpp.enable = true
                outSms.instance.smpp.type = smppserver
                outSms.instance.smpp.tps = 0
                outSms.instance.smpp.print.msgs = false
                outSms.instance.smpp.srv.host = 0.0.0.0
                outSms.instance.smpp.srv.port = 27777
                outSms.instance.smpp.srv.bindTimeout = 5000
                outSms.instance.smpp.srv.defaultRequestExpiryTimeout = 30000
                outSms.instance.smpp.srv.defaultWindowMonitorInterval = 15000
                outSms.instance.smpp.srv.defaultWindowSize = 1000
                outSms.instance.smpp.srv.maxConnections = 1000
                outSms.instance.smpp.srv.maxConnectionsPerIP = 4
                outSms.instance.smpp.conf.maxConnectionsPerUser.default = 4
                outSms.instance.smpp.conf.maxRate.default = 0
                """, StandardCharsets.UTF_8);

        Files.writeString(conf.resolve("routingTable.conf"), """
                [default]
                MESSAGE:type:==:0
                MESSAGE:type:==:11
                MESSAGE:type:==:14
                MESSAGE:type:==:17
                MESSAGE:type:==:10
                smppserver.smpp:type:==:18

                [MESSAGE]
                route::default:
                """, StandardCharsets.UTF_8);
    }

    private static void waitForPort(String host, int port, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket ignored = new Socket(host, port)) {
                return;
            } catch (IOException ignored) {
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("Timed out waiting for " + host + ':' + port);
    }

    private static void waitForDlrReadiness() throws Exception {
        waitForDlrReadiness(DLR_BACKEND);
    }

    private static void waitForDlrReadiness(String backend) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> response = get("/q/health/ready");
                String body = response.body().replaceAll("\\s", "");
                if (response.statusCode() == HttpURLConnection.HTTP_OK
                        && body.contains("\"name\":\"sendium-dlr-storage\"")
                        && body.contains("\"backend\":\"" + backend + "\"")) {
                    return;
                }
            } catch (IOException ignored) {
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Timed out waiting for " + backend + "-backed Sendium readiness");
    }

    private static void awaitSuccessfulStorageOperation(String operation, double countBeforeSubmission) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = get("/q/metrics");
            if (response.statusCode() == HttpURLConnection.HTTP_OK
                    && hasSuccessfulStorageOperation(response.body(), operation, countBeforeSubmission)) {
                return;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Timed out waiting for successful DLR storage operation: " + operation);
    }

    static boolean hasSuccessfulStorageOperation(String metrics, String operation, double countBeforeSubmission) {
        return successfulStorageOperationCount(metrics, operation) > countBeforeSubmission;
    }

    static double successfulStorageOperationCount(String metrics, String operation) {
        return successfulStorageOperationCount(metrics, DLR_BACKEND, operation);
    }

    static double successfulStorageOperationCount(String metrics, String backend, String operation) {
        return metrics.lines()
                .filter(line -> line.startsWith("sendium_dlr_storage_operation_seconds_count"))
                .filter(line -> line.contains("backend=\"" + backend + "\""))
                .filter(line -> line.contains("operation=\"" + operation + "\""))
                .filter(line -> line.contains("outcome=\"success\""))
                .map(line -> line.substring(line.lastIndexOf(' ') + 1))
                .mapToDouble(value -> {
                    try {
                        double count = Double.parseDouble(value);
                        return Double.isFinite(count) && count >= 0 ? count : 0;
                    } catch (NumberFormatException ignored) {
                        return 0;
                    }
                }).max().orElse(0);
    }

    private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + SENDIUM_HTTP_PORT + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Process run(List<String> command, boolean inheritOutput) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (inheritOutput) {
            builder.inheritIO();
        }
        return builder.start();
    }

    private static void printDockerLogs(String containerName) {
        try {
            run(List.of("docker", "logs", containerName), true).waitFor(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private static String readDockerLogs(String name) throws Exception {
        var logs = new ProcessBuilder("docker", "logs", name).redirectErrorStream(true).start();
        String output = new String(logs.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        require(logs.waitFor(10, TimeUnit.SECONDS) && logs.exitValue() == 0, "Cannot read container logs");
        return output;
    }

    private static boolean stopContainer(String containerName) {
        try {
            Process process = run(List.of("docker", "rm", "--force", containerName), true);
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class CallbackServer implements AutoCloseable {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final List<String> queries = Collections.synchronizedList(new ArrayList<>());
        private final List<Long> attemptNanos = Collections.synchronizedList(new ArrayList<>());
        private final HttpServer server;
        private volatile boolean failAll;

        // The first `failures` callbacks receive HTTP 500, so Sendium must persist and retry them.
        private CallbackServer(int failures) throws IOException {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
            server.createContext("/dlr", exchange -> {
                queries.add(exchange.getRequestURI().getRawQuery());
                attemptNanos.add(System.nanoTime());
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(failAll || queries.size() <= failures ? 500 : 200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                latch.countDown();
            });
        }

        private boolean awaitCallbacks(int expected) throws InterruptedException {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (queries.size() < expected && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            return queries.size() >= expected;
        }

        private boolean awaitGatewayIds(List<String> ids) throws InterruptedException {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                List<String> observed;
                synchronized (queries) {
                    observed = List.copyOf(queries);
                }
                if (ids.stream().allMatch(id -> observed.stream().anyMatch(query -> query.contains("id=" + id)))) {
                    return true;
                }
                Thread.sleep(100);
            }
            return false;
        }

        private String query(int index) {
            return queries.get(index);
        }

        private int count() {
            return queries.size();
        }

        private void start() {
            server.start();
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private String awaitCallback() throws InterruptedException {
            if (!latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                return null;
            }
            return queries.getFirst();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class UpstreamSmppServer implements AutoCloseable {
        private final int port;
        private final AtomicInteger receivedSubmits = new AtomicInteger();
        private final Set<String> receivedBodies = ConcurrentHashMap.newKeySet();
        private final AtomicInteger boundSessions = new AtomicInteger();
        private final CountDownLatch sessionBound = new CountDownLatch(1);
        private final Set<SmppSession> sessions = Collections.newSetFromMap(new ConcurrentHashMap<>());
        private final Map<Integer, PendingDeliveryReceipt> pendingDeliveryReceipts = new ConcurrentHashMap<>();
        private final DefaultSmppServer server;
        private volatile boolean automaticDelivery = true;

        private UpstreamSmppServer(int port) {
            this.port = port;
            SmppServerConfiguration configuration = new SmppServerConfiguration();
            configuration.setDefaultWindowSize(1000);
            configuration.setPort(port);

            SmppSessionHandler sessionHandler = new DefaultSmppSessionHandler() {
                @Override
                public PduResponse firePduRequestReceived(PduRequest pduRequest) {
                    if (!(pduRequest instanceof SubmitSm submitSm)) {
                        return pduRequest.createResponse();
                    }
                    if (!MEMORY_DLR && !DISABLED_DLR) {
                        require(submitSm.getRegisteredDelivery() == SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED,
                                "Sendium did not request an upstream delivery receipt");
                    }
                    int submitNumber = receivedSubmits.incrementAndGet();
                    receivedBodies.add(new String(submitSm.getShortMessage(), StandardCharsets.UTF_8));
                    String messageId = "e2e-" + submitNumber;
                    pendingDeliveryReceipts.put(submitNumber,
                            new PendingDeliveryReceipt(submitSm.getDestAddress(), submitSm.getSourceAddress(), messageId));
                    SubmitSmResp response = (SubmitSmResp) pduRequest.createResponse();
                    response.setMessageId(messageId);
                    if (automaticDelivery && submitSm.getRegisteredDelivery() == SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED) {
                        Thread.ofVirtual().start(() -> sendDeliveryReceiptAfterDelay(submitNumber));
                    }
                    return response;
                }
            };

            SmppServerHandler bindHandler = new SmppServerHandler() {
                @Override
                public void sessionBindRequested(Long sessionId, SmppSessionConfiguration sessionConfiguration,
                                                 BaseBind bindRequest) throws SmppProcessingException {
                    sessionConfiguration.setName("upstream-e2e");
                }

                @Override
                public void sessionCreated(Long sessionId, SmppServerSession session,
                                            BaseBindResp preparedBindResponse) throws SmppProcessingException {
                    session.serverReady(sessionHandler);
                    sessions.add(session);
                    boundSessions.incrementAndGet();
                    sessionBound.countDown();
                }

                @Override
                public void sessionDestroyed(Long sessionId, SmppServerSession session) {
                    sessions.remove(session);
                }
            };

            server = new DefaultSmppServer(
                    configuration,
                    bindHandler,
                    null,
                    new NioEventLoopGroup(1, new ThreadFactoryBuilder().setNameFormat("upstream-boss-%d").setDaemon(true).build()),
                    new NioEventLoopGroup(2, new ThreadFactoryBuilder().setNameFormat("upstream-worker-%d").setDaemon(true).build())
            );
        }

        private void start() throws SmppChannelException {
            server.start();
        }

        private boolean awaitSubmitCount(int expected) throws InterruptedException {
            if (receivedSubmits.get() >= expected) {
                return true;
            }
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                if (receivedSubmits.get() >= expected) {
                    return true;
                }
                Thread.sleep(100);
            }
            return false;
        }

        private boolean awaitBodies(List<String> expected) throws InterruptedException {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                if (receivedBodies.containsAll(expected)) {
                    return true;
                }
                Thread.sleep(100);
            }
            return false;
        }

        private boolean awaitSessionBound() throws InterruptedException {
            return sessionBound.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        private int boundSessionCount() {
            return boundSessions.get();
        }

        private boolean awaitSessionBoundAfter(int previousCount) throws InterruptedException {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                if (boundSessions.get() > previousCount) {
                    return true;
                }
                Thread.sleep(500);
            }
            return false;
        }

        private void setAutomaticDelivery(boolean automaticDelivery) {
            this.automaticDelivery = automaticDelivery;
        }

        private void sendDeliveryReceiptAfterDelay(int submitNumber) {
            try {
                Thread.sleep(1_000);
                sendDeliveryReceipt(submitNumber);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to send delayed upstream delivery receipt on port " + port, e);
            }
        }

        private void sendDeliveryReceipt(int submitNumber) {
            PendingDeliveryReceipt pendingReceipt = pendingDeliveryReceipts.get(submitNumber);
            require(pendingReceipt != null, "No pending delivery receipt for upstream submit " + submitNumber);
            try {
                DeliverSm deliverSm = new DeliverSm();
                deliverSm.setDataCoding(SmppConstants.DATA_CODING_DEFAULT);
                deliverSm.setEsmClass(SmppConstants.ESM_CLASS_MT_SMSC_DELIVERY_RECEIPT);
                deliverSm.setSourceAddress(pendingReceipt.sourceAddress());
                deliverSm.setDestAddress(pendingReceipt.destinationAddress());
                String dlr = "id:" + pendingReceipt.messageId()
                        + " sub:001 dlvrd:001 submit date:2605191200 done date:2605191200 stat:DELIVRD err:000 text:e2e";
                deliverSm.setShortMessage(dlr.getBytes(StandardCharsets.UTF_8));
                for (SmppSession session : sessions) {
                    if (session.isBound() && session.getBindType() != SmppBindType.TRANSMITTER) {
                        var future = session.sendRequestPdu(deliverSm, 30_000, true);
                        require(future.await(30_000), "Timed out waiting for Sendium to acknowledge upstream delivery receipt");
                        require(future.isSuccess(), "Sendium failed to acknowledge upstream delivery receipt: " + future.getCause());
                        PduResponse response = future.getResponse();
                        require(response.getCommandStatus() == SmppConstants.STATUS_OK,
                                "Sendium rejected upstream delivery receipt with status " + response.getCommandStatus());
                        pendingDeliveryReceipts.remove(submitNumber, pendingReceipt);
                        return;
                    }
                }
                throw new IllegalStateException("No bound Sendium session for upstream delivery receipt");
            } catch (Exception e) {
                throw new IllegalStateException("Failed to send upstream delivery receipt on port " + port, e);
            }
        }

        private record PendingDeliveryReceipt(Address sourceAddress, Address destinationAddress, String messageId) {
        }

        @Override
        public void close() {
            server.destroy(0, 100);
        }
    }

    private static final class DownstreamSmppClient implements AutoCloseable {
        private final CountDownLatch deliverSmLatch = new CountDownLatch(1);
        private final AtomicInteger receivedDeliveries = new AtomicInteger();
        private final DefaultSmppClient client;
        private volatile DeliverSm deliverSm;
        private SmppSession session;
        private final String systemId;

        private DownstreamSmppClient() {
            this("smpp-user");
        }

        private DownstreamSmppClient(String systemId) {
            this.systemId = systemId;
            client = new DefaultSmppClient(new NioEventLoopGroup(
                    1,
                    new ThreadFactoryBuilder().setNameFormat("downstream-client-%d").setDaemon(true).build()
            ));
        }

        private void start() throws Exception {
            Exception lastFailure = null;
            int attempt = 0;
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                attempt++;
                try {
                    session = client.bind(createConfiguration(), createSessionHandler());
                    return;
                } catch (Exception e) {
                    lastFailure = e;
                    if (session != null) {
                        session.destroy();
                        session = null;
                    }
                    System.err.println("Downstream SMPP bind attempt " + attempt + " failed: "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                    Thread.sleep(500);
                }
            }
            throw new IllegalStateException("Downstream SMPP client could not bind after " + attempt + " attempts", lastFailure);
        }

        private SmppSessionConfiguration createConfiguration() {
            SmppSessionConfiguration configuration = new SmppSessionConfiguration(SmppBindType.TRANSCEIVER, systemId, "smpp-pass");
            configuration.setHost("localhost");
            configuration.setPort(SENDIUM_SMPP_PORT);
            configuration.setWindowSize(1000);
            return configuration;
        }

        private DefaultSmppSessionHandler createSessionHandler() {
            return new DefaultSmppSessionHandler() {
                @Override
                public PduResponse firePduRequestReceived(PduRequest pduRequest) {
                    if (pduRequest instanceof DeliverSm receivedDeliverSm) {
                        deliverSm = receivedDeliverSm;
                        receivedDeliveries.incrementAndGet();
                        deliverSmLatch.countDown();
                    }
                    return pduRequest.createResponse();
                }
            };
        }

        private SubmitSmResp sendSms(String from, String to, String text) throws SmppInvalidArgumentException, InterruptedException,
                com.cloudhopper.smpp.type.SmppTimeoutException, com.cloudhopper.smpp.type.SmppChannelException,
                com.cloudhopper.smpp.type.UnrecoverablePduException, com.cloudhopper.smpp.type.RecoverablePduException {
            return sendSms(from, to, text, true);
        }

        private SubmitSmResp sendSms(String from, String to, String text, boolean dlr) throws SmppInvalidArgumentException, InterruptedException,
                com.cloudhopper.smpp.type.SmppTimeoutException, com.cloudhopper.smpp.type.SmppChannelException,
                com.cloudhopper.smpp.type.UnrecoverablePduException, com.cloudhopper.smpp.type.RecoverablePduException {
            SubmitSm submit = new SubmitSm();
            submit.setSourceAddress(new Address((byte) 0, (byte) 0, from));
            submit.setDestAddress(new Address((byte) 0, (byte) 0, to));
            submit.setRegisteredDelivery(dlr ? SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED : (byte) 0);
            submit.setDataCoding(SmppConstants.DATA_CODING_DEFAULT);
            submit.setShortMessage(text.getBytes(StandardCharsets.UTF_8));
            return session.submit(submit, 10_000);
        }

        private SubmitSmResp sendPart(int ordinal, String text) throws Exception {
            return sendPart(ordinal, text, 127);
        }

        private SubmitSmResp sendPart(int ordinal, String text, int reference) throws Exception {
            var submit = new SubmitSm();
            submit.setSourceAddress(new Address((byte) 0, (byte) 0, "multipart-sender"));
            submit.setDestAddress(new Address((byte) 0, (byte) 0, "306900000003"));
            submit.setRegisteredDelivery(SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED);
            submit.setEsmClass(SmppConstants.ESM_CLASS_UDHI_MASK);
            submit.setDataCoding(SmppConstants.DATA_CODING_DEFAULT);
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            byte[] data = new byte[6 + body.length];
            byte[] header = {5, 0, 3, (byte) reference, 2, (byte) ordinal};
            System.arraycopy(header, 0, data, 0, header.length);
            System.arraycopy(body, 0, data, header.length, body.length);
            submit.setShortMessage(data);
            return session.submit(submit, 10_000);
        }

        private DeliverSm awaitDeliverSm() throws InterruptedException {
            return awaitDeliverSm(TIMEOUT);
        }

        private DeliverSm awaitDeliverSm(Duration timeout) throws InterruptedException {
            if (!deliverSmLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return null;
            }
            return deliverSm;
        }

        @Override
        public void close() {
            if (session != null) {
                session.destroy();
            }
            client.destroy(0, 0);
        }
    }
}
