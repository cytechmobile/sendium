package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.message.DlrReturnMetadata;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.DefaultOutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundDlrPolicy;
import gr.cytech.sendium.core.outbound.OutboundWork.Routed;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.outbound.OutboundWork.WorkId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.RoutedWorkStore;
import gr.cytech.sendium.core.storage.file.RoutedRecordCodec.StoredWork;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import gr.cytech.sendium.core.worker.MemoryDlrReservations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

class FileRoutedDeferralTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void eligibleWorkBeyondBlockedWindowProgresses(boolean disabled) throws Exception {
        try (var pending = new FilePendingMessageStore(root, 32); var routed = new FileRoutedWorkStore(pending, 32, 2)) {
            pending.open();
            routed.open();
            seed(pending, 10, 3);
            seed(pending, 20, 3);
            seed(pending, 30, 1);
            seed(pending, 40, 0);
        }
        var owner = new MemoryDlrReservations(2);
        OutboundDlrPolicy policy = disabled ? disabledPolicy() : owner;
        var pending = new FilePendingMessageStore(root, 32);
        var routed = new FileRoutedWorkStore(pending, 32, 2);
        try (var coordinator = new DefaultOutboundCoordinator<>(pending, new MemorySelectedRouterStore<>(pending, 32),
                routed, 2, policy)) {
            coordinator.start();
            var seen = new HashSet<WorkId>();
            for (int cycle = 0; cycle < 8; cycle++) {
                var work = coordinator.takeFromRouted("provider", Duration.ZERO);
                if (work.isPresent()) {
                    var taken = work.orElseThrow();
                    seen.add(taken.id());
                    coordinator.complete(taken.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
                }
                assertThat(active(routed).size()).isLessThanOrEqualTo(2);
                assertThat(runtimeContexts(coordinator).size()).isLessThanOrEqualTo(2);
            }
            assertThat(seen).contains(new WorkId(new UUID(0, 40)));
            if (!disabled) {
                assertThat(seen).contains(new WorkId(new UUID(0, 30)));
                var next = message(99, true);
                assertThatThrownBy(() -> coordinator.accept(source(99, 0), next))
                        .isInstanceOf(OutboundStorageException.class);
            } else {
                assertThat(seen).doesNotContain(new WorkId(new UUID(0, 30)));
            }
            assertThat(pending.find(source(10, 0))).isPresent();
            assertThat(pending.find(source(20, 0))).isPresent();
            assertThat(routed.sourcesFor(new WorkId(new UUID(0, 10)))).hasSize(3);
        }
    }

    @Test
    void deferredWorkSurvivesReopenWithoutRerouting() throws Exception {
        Routed<StandardMessage> original;
        try (var pending = new FilePendingMessageStore(root, 8); var routed = new FileRoutedWorkStore(pending, 8, 1)) {
            pending.open();
            routed.open();
            seed(pending, 10, 1);
            original = routed.take("provider", Duration.ZERO).orElseThrow();
            defer(routed, original);
            assertThat(active(routed)).isEmpty();
            assertThat(pending.find(source(10, 0))).isPresent();
            assertThatThrownBy(() -> routed.complete(original.id())).isInstanceOf(RuntimeException.class);
        }
        try (var pending = new FilePendingMessageStore(root, 8); var routed = new FileRoutedWorkStore(pending, 8, 1)) {
            pending.open();
            routed.open();
            var restored = routed.take("provider", Duration.ZERO).orElseThrow();
            assertThat(restored.id()).isEqualTo(original.id());
            assertThat(restored.selection()).isEqualTo(original.selection());
            assertThat(restored.destination()).isEqualTo("provider");
            assertThat(restored.message().serial).isEqualTo(original.message().serial);
            assertThat(routed.take("other", Duration.ZERO)).isEmpty();
        }
    }

    @Test
    void cursorSurvivesRemovalAndWrapsAcrossInsertions() throws Exception {
        try (var pending = new FilePendingMessageStore(root, 8); var routed = new FileRoutedWorkStore(pending, 8, 1)) {
            pending.open();
            routed.open();
            seed(pending, 10, 0);
            seed(pending, 20, 0);
            seed(pending, 30, 0);
            var first = routed.take("provider", Duration.ZERO).orElseThrow();
            assertThat(first.id()).isEqualTo(new WorkId(new UUID(0, 10)));
            defer(routed, first);
            var middle = routed.take("provider", Duration.ZERO).orElseThrow();
            assertThat(middle.id()).isEqualTo(new WorkId(new UUID(0, 20)));
            pending.complete(routed.complete(middle.id()).sources());
            routed.forget(middle.selection());
            seed(pending, 5, 0);
            var later = routed.take("provider", Duration.ZERO).orElseThrow();
            assertThat(later.id()).isEqualTo(new WorkId(new UUID(0, 30)));
            defer(routed, later);
            var wrapped = routed.take("provider", Duration.ZERO).orElseThrow();
            assertThat(wrapped.id()).isEqualTo(new WorkId(new UUID(0, 5)));
            defer(routed, wrapped);
            var again = routed.take("provider", Duration.ZERO).orElseThrow();
            assertThat(again.id()).isEqualTo(first.id());
            assertThatThrownBy(() -> defer(routed, first)).isInstanceOf(RuntimeException.class);
            assertThat(active(routed)).hasSize(1);
        }
    }

    @Test
    void explicitCoordinatorDeferralKeepsAcceptedReservation() throws Exception {
        try (var pending = new FilePendingMessageStore(root, 8); var routed = new FileRoutedWorkStore(pending, 8, 1)) {
            pending.open();
            routed.open();
            seed(pending, 10, 1);
        }
        var owner = new MemoryDlrReservations(2);
        var pending = new FilePendingMessageStore(root, 8);
        var routed = new FileRoutedWorkStore(pending, 8, 1);
        try (var coordinator = new DefaultOutboundCoordinator<>(pending, new MemorySelectedRouterStore<>(pending, 8),
                routed, 2, owner)) {
            coordinator.start();
            var taken = coordinator.takeFromRouted("provider", Duration.ZERO).orElseThrow();
            try {
                DefaultOutboundCoordinator.class.getMethod("deferFromRouted", Routed.class).invoke(coordinator, taken);
            } catch (NoSuchMethodException missing) {
                throw new AssertionError("Coordinator must support explicit pre-provider deferral", missing);
            }
            assertThat(owner.usedCapacity()).isEqualTo(1);
            assertThat(active(routed)).isEmpty();
            assertThat(runtimeContexts(coordinator)).isEmpty();
            assertThat(pending.find(source(10, 0))).isPresent();
            assertThat(coordinator.takeFromRouted("provider", Duration.ZERO).orElseThrow().id()).isEqualTo(taken.id());
            assertThat(owner.usedCapacity()).isEqualTo(1);
        }
    }

    @Test
    void unsupportedCustomStoreRejectsDeferral() {
        RoutedWorkStore<StandardMessage> custom = mock(RoutedWorkStore.class, CALLS_REAL_METHODS);
        var work = new Routed<>(new WorkId(UUID.randomUUID()), new SelectionId(UUID.randomUUID()), "provider", message(1, false));
        assertThatThrownBy(() -> defer(custom, work))
                .isInstanceOfSatisfying(OutboundStorageException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(OutboundStorageException.Reason.UNSUPPORTED));
    }

    private static void seed(FilePendingMessageStore pending, int key, int dlrSources) {
        var sources = new HashSet<SourceId>();
        StandardMessage projection = null;
        for (int part = 0; part < Math.max(1, dlrSources); part++) {
            var message = message(key * 100 + part, dlrSources > 0);
            var source = source(key, part);
            message.serial = source.value().toString();
            pending.admit(source, message);
            sources.add(source);
            projection = message;
        }
        pending.selectedSpool().publishRouted(new StoredWork(new Routed<>(new WorkId(new UUID(0, key)),
                new SelectionId(new UUID(1, key)), "provider", projection), sources));
    }

    private static OutboundDlrPolicy disabledPolicy() {
        return new OutboundDlrPolicy() {
            @Override
            public Reservation reserveAdmission(SourceId source, StandardMessage message) {
                return Reservation.UNCHANGED;
            }

            @Override
            public void prepareProvider(String id, Set<SourceId> required) {
                if (!required.isEmpty()) {
                    throw new Blocked(BlockReason.DISABLED, required.size(), 0);
                }
            }
        };
    }

    private static StandardMessage message(int key, boolean dlr) {
        var message = new StandardMessage();
        message.serial = new UUID(2, key).toString();
        message.body = "synthetic";
        if (dlr) {
            message.dlrReturnMetadata = DlrReturnMetadata.http("account", "sender", "recipient", "https://callback.test");
        }
        return message;
    }

    private static SourceId source(int key, int part) {
        return new SourceId(new UUID(2, key * 100L + part));
    }

    private static Map<?, ?> active(FileRoutedWorkStore store) throws ReflectiveOperationException {
        var field = FileRoutedWorkStore.class.getDeclaredField("active");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(store);
    }

    private static Map<?, ?> runtimeContexts(DefaultOutboundCoordinator<?> coordinator) throws ReflectiveOperationException {
        var field = DefaultOutboundCoordinator.class.getDeclaredField("selections");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(coordinator);
    }

    /** Reflection lets the baseline compile, so new behavior fails assertions rather than missing APIs. */
    private static void defer(RoutedWorkStore<StandardMessage> store, Routed<StandardMessage> work) throws Exception {
        try {
            RoutedWorkStore.class.getMethod("defer", Routed.class).invoke(store, work);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("Routed storage must support explicit deferral", missing);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw failure;
        }
    }
}
