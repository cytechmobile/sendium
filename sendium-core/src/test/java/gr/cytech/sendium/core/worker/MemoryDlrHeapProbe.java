package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.DlrReturnMetadata;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.worker.MessageState.DeliveryChannel;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Isolated synthetic DLR state only; not a packaged-application or host-memory qualification. */
public final class MemoryDlrHeapProbe {
    static final int CAPACITY = 100_000;

    private MemoryDlrHeapProbe() {
    }

    public static void main(String[] args) throws Exception {
        try (var warmup = new Fixture(3_200)) {
            warmup.populate();
            warmup.assertOverloadAndAliasReuse();
            warmup.drain();
        }
        try (var fixture = new Fixture(CAPACITY)) {
            long[] baseline = heapSamples();
            long gcBefore = gcCount();
            long gcMillisBefore = gcMillis();
            long started = System.nanoTime();
            fixture.populate();
            long populateMillis = (System.nanoTime() - started) / 1_000_000;
            fixture.assertOverloadAndAliasReuse();
            fixture.assertOccupied();
            long[] occupied = heapSamples();
            long rss = residentBytes();
            fixture.drain();
            long[] cleaned = heapSamples();
            long baselineHeap = median(baseline);
            long occupiedHeap = median(occupied);
            check(occupiedHeap > baselineHeap, "occupiedHeapMustExceedBaseline");
            System.out.printf("memoryDlrProbe|success|scope:isolatedDlrOnly|java:%s|os:%s|arch:%s|gc:%s" +
                            "|maxHeapBytes:%d|sources:%d|executions:46875|receipts:46875|liveCorrelations:62500" +
                            "|acceptedProviderIds:187500|retainedAcceptanceKeys:62500|accountChars:64|systemChars:64|providerIdChars:64|callbackChars:1024" +
                            "|baselineSamples:%s|occupiedSamples:%s|cleanedSamples:%s|baselineHeapBytes:%d" +
                            "|occupiedHeapBytes:%d|incrementalBytes:%d|bytesPerSource:%.2f|cleanedHeapBytes:%d" +
                            "|rssBytes:%d|populateMillis:%d|gcCollections:%d|gcMillis:%d|cleanup:empty|fullApplication:UNQUALIFIED%n",
                    safe(System.getProperty("java.runtime.version")), safe(System.getProperty("os.name")),
                    safe(System.getProperty("os.arch")),
                    safe(ManagementFactory.getGarbageCollectorMXBeans().stream().map(bean -> bean.getName()).toList().toString()),
                    Runtime.getRuntime().maxMemory(), CAPACITY, Arrays.toString(baseline), Arrays.toString(occupied),
                    Arrays.toString(cleaned), baselineHeap, occupiedHeap, occupiedHeap - baselineHeap,
                    (double) (occupiedHeap - baselineHeap) / CAPACITY, median(cleaned), rss,
                    populateMillis, gcCount() - gcBefore, gcMillis() - gcMillisBefore);
        }
    }

    private static long[] heapSamples() throws InterruptedException {
        long[] samples = new long[5];
        for (int i = 0; i < samples.length; i++) {
            long before = gcCount();
            System.gc();
            for (int wait = 0; gcCount() <= before && wait < 200; wait++) {
                Thread.sleep(10);
            }
            check(gcCount() > before, "explicitGcNotObserved");
            samples[i] = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        }
        return samples;
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
    }

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
    }

    private static long median(long[] samples) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static long residentBytes() throws java.io.IOException {
        // RSS is optional evidence. Committed virtual memory is not an RSS substitute.
        if (!System.getProperty("os.name").equals("Linux")) {
            return -1;
        }
        Path status = Path.of("/proc/self/status");
        if (!Files.isReadable(status)) {
            return -1;
        }
        return residentBytes(Files.readAllLines(status));
    }

    static long residentBytes(List<String> status) {
        for (String line : status) {
            if (line.startsWith("VmRSS:")) {
                try {
                    long kibibytes = Long.parseLong(line.substring(6).strip().split("\\s+")[0]);
                    return kibibytes < 0 ? -1 : Math.multiplyExact(kibibytes, 1024);
                } catch (NumberFormatException | ArithmeticException invalidRss) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static String safe(String value) {
        return value.replace('|', '_').replace('\r', '_').replace('\n', '_');
    }

    static void check(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalStateException("memoryDlrProbe|error|reason:" + reason);
        }
    }

    static final class Fixture implements AutoCloseable {
        final MemoryDlrReservations owner;
        final MemoryDlrStorage storage;
        private final MemoryDlrTestSupport.MutableClock clock = new MemoryDlrTestSupport.MutableClock();
        private final int capacity;
        private final int quarter;

        Fixture(int capacity) {
            check(capacity > 0 && capacity % 32 == 0, "invalidFixtureCapacity");
            this.capacity = capacity;
            quarter = capacity / 4;
            owner = new MemoryDlrReservations(capacity);
            storage = new MemoryDlrStorage(owner, Duration.ofDays(3), Duration.ofDays(7), Duration.ofMinutes(1), clock);
        }

        void populate() {
            for (int i = 0; i < quarter; i++) {
                reserve(i);
            }
            for (int bucket = 1; bucket <= 3; bucket++) {
                for (int offset = 0; offset < quarter;) {
                    int size = offset < quarter / 2 ? 1 : 4;
                    int first = bucket * quarter + offset;
                    Set<SourceId> sources = sources(first, size);
                    for (int part = 0; part < size; part++) {
                        reserve(first + part);
                    }
                    var state = state(first, bucket);
                    if (size > 1) {
                        state.setReassembledParts(sources.stream().map(source -> source.value().toString()).sorted().toList());
                    }
                    owner.prepareProvider(state.getGatewayMsgId(), sources);
                    // Four IDs model provider splits and a replacement, not four ingress slots.
                    for (int alias = 0; alias < 4; alias++) {
                        storage.recordProviderAccepted(state, "P", providerId(first, alias));
                    }
                    if (bucket >= 2) {
                        storage.resolveDlr("P", providerId(first, 0), MessageState.MessageStatus.DELIVERED, 1, "0").orElseThrow();
                    }
                    if (bucket == 2) {
                        var attempt = storage.startDeliveryAttempt(state.getGatewayMsgId(), DeliveryChannel.HTTP).orElseThrow();
                        check(storage.retryDelivery(state.getGatewayMsgId(), attempt.getDeliveryAttemptCount(),
                                "syntheticRetry", 30_000), "httpRetry");
                    }
                    owner.releaseOutbound(sources);
                    offset += size;
                }
            }
        }

        void assertOccupied() throws ReflectiveOperationException {
            int executionsPerBucket = quarter / 2 + quarter / 8;
            check(owner.usedCapacity() == capacity, "occupancy");
            check(size(owner, "sources") == capacity, "sourceCount");
            check(size(owner, "executions") == executionsPerBucket * 3, "executionCount");
            var field = MemoryDlrReservations.class.getDeclaredField("executions");
            field.setAccessible(true);
            var executions = (Map<?, ?>) field.get(owner);
            long providerIds = executions.values().stream().mapToLong(value ->
                    ((MemoryDlrReservations.Execution) value).firstCorrelations.size()).sum();
            check(providerIds == executionsPerBucket * 4L, "retainedProviderIdCount");
            check(size(storage, "receipts") == executionsPerBucket * 3, "receiptCount");
            check(size(storage, "correlations") == executionsPerBucket * 4, "correlationCount");
            check(size(storage, "httpDue") == executionsPerBucket, "httpDueCount");
            check(size(storage, "smppPending") == executionsPerBucket, "smppSystemCount");
            check(size(storage, "expirations") == executionsPerBucket * 6, "expiryCount");
            var counts = storage.readMetricSnapshot(capacity, Duration.ofSeconds(1)).orElseThrow();
            check(counts.equals(new DlrStorageBackend.ReceiptCounts(executionsPerBucket, executionsPerBucket * 2L, 0)),
                    "receiptLifecycleCounts");
        }

        void assertOverloadAndAliasReuse() {
            try {
                reserve(capacity);
                throw new IllegalStateException("memoryDlrProbe|error|reason:overloadAccepted");
            } catch (OutboundStorageException failure) {
                check(failure.reason() == OutboundStorageException.Reason.CAPACITY_EXCEEDED, "overloadReason");
            }
            int first = quarter;
            var sources = sources(first, 1);
            var state = state(first, 1);
            owner.prepareProvider(state.getGatewayMsgId(), sources);
            storage.recordProviderAccepted(state, "P", providerId(first, 3));
            check(owner.usedCapacity() == capacity, "replacementAllocatedCapacity");
            owner.releaseOutbound(sources);
        }

        void drain() throws ReflectiveOperationException {
            // Exercise successful HTTP delivery before expiring unresolved/SMPP obligations.
            clock.set(31_000);
            int delivered = 0;
            List<MessageState> due;
            do {
                due = storage.claimDueHttpDeliveries(1_000);
                for (var state : due) {
                    check(storage.completeDelivery(state.getGatewayMsgId(), state.getDeliveryAttemptCount()), "httpCompletion");
                    delivered++;
                }
            } while (!due.isEmpty());
            check(delivered == quarter / 2 + quarter / 8, "deliveredHttpCount");
            for (int i = 0; i < quarter; i++) {
                owner.releaseOutbound(Set.of(source(i)));
            }
            clock.set(Duration.ofDays(8).toMillis());
            int inspected;
            do {
                inspected = storage.cleanup(1_000);
                check(inspected <= 1_000, "cleanupBatchExceeded");
            } while (inspected != 0);
            check(owner.usedCapacity() == 0, "capacityRetainedAfterCleanup");
            for (String field : List.of("sources", "executions")) {
                check(size(owner, field) == 0, "retainedOwnerIndex:" + field);
            }
            for (String field : List.of("receipts", "correlations", "expirations", "httpDue", "smppPending")) {
                check(size(storage, field) == 0, "retainedReceiptIndex:" + field);
            }
            check(storage.readMetricSnapshot(capacity, Duration.ofSeconds(1)).orElseThrow()
                    .equals(new DlrStorageBackend.ReceiptCounts(0, 0, 0)), "retainedLifecycleCount");
        }

        private void reserve(int index) {
            var message = new StandardMessage();
            message.dlrReturnMetadata = DlrReturnMetadata.smpp("synthetic", "synthetic", "sender", "recipient");
            owner.reserveAdmission(source(index), message);
        }

        private static SourceId source(int index) {
            return new SourceId(new UUID(0, index + 1L));
        }

        private static Set<SourceId> sources(int first, int size) {
            var sources = new HashSet<SourceId>();
            for (int part = 0; part < size; part++) {
                sources.add(source(first + part));
            }
            return Set.copyOf(sources);
        }

        private static MessageState state(int first, int bucket) {
            String id = source(first).value().toString();
            var state = new MessageState(id, padded("account-" + id, 64), padded("system-" + id, 64),
                    "sender", "recipient", bucket == 3 ? null : padded("https://callback.test/" + id + "/", 1_024));
            state.setDeliveryChannel(bucket == 3 ? DeliveryChannel.SMPP : DeliveryChannel.HTTP);
            state.setHttpCallbackFormat(DlrReturnMetadata.HttpCallbackFormat.JSON_POST);
            return state;
        }

        private static String providerId(int first, int alias) {
            return padded("provider-" + source(first).value() + "-" + alias, 64);
        }

        private static String padded(String prefix, int length) {
            return (prefix + "x".repeat(length)).substring(0, length);
        }

        private static int size(Object object, String name) throws ReflectiveOperationException {
            var field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            var value = field.get(object);
            return value instanceof Map<?, ?> map ? map.size() : ((java.util.Collection<?>) value).size();
        }

        @Override
        public void close() {
            storage.close();
        }
    }
}
