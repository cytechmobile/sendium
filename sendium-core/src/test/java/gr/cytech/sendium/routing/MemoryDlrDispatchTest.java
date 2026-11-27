package gr.cytech.sendium.routing;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.pdu.SubmitSm;
import gr.cytech.sendium.conf.SendiumConfigurationProvider;
import gr.cytech.sendium.core.message.DlrReturnMetadata;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.DefaultOutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundDlrPolicy;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.smpp.client.CoordinatedSmppSubmission;
import gr.cytech.sendium.core.smpp.client.SmppClientWorker;
import gr.cytech.sendium.core.storage.memory.MemoryPendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemoryRoutedWorkStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import gr.cytech.sendium.core.worker.DlrService;
import gr.cytech.sendium.core.worker.DlrStorage;
import gr.cytech.sendium.core.worker.DlrStorageException;
import gr.cytech.sendium.core.worker.MemoryDlrReservations;
import gr.cytech.sendium.core.worker.MessageState;
import gr.cytech.sendium.core.worker.StandardMessageTracker;
import gr.cytech.sendium.external.WorkerResourceProvider;
import gr.cytech.sendium.util.StatsKeeper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Timeout(30)
class MemoryDlrDispatchTest {
    @Test
    void replacementAttemptReusesProtectedSourceSet() throws Exception {
        var policy = new Policy(false);
        try (var harness = new Harness(policy)) {
            harness.provider.replaceFirst = true;
            var execution = harness.submit(message(true));
            harness.provider.respond(harness.provider.next(), "replaced");
            var replacement = harness.provider.next();
            assertThat(policy.owner.usedCapacity()).isEqualTo(1);
            harness.provider.respond(replacement, "replacement");
            execution.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(harness.provider.sends).hasValue(2);
            verify(harness.coordinator, times(1)).takeFromRouted(any(), any());
            assertThat(policy.owner.usedCapacity()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void postFilterRequirementCannotBypassProtection(boolean disabled) throws Exception {
        var policy = new Policy(disabled);
        var unrelated = source(message(true));
        if (!disabled) {
            policy.owner.reserveAdmission(unrelated, message(true));
        }
        try (var harness = new Harness(policy)) {
            String changedSerial = UUID.randomUUID().toString();
            harness.provider.filter = message -> {
                message.serial = changedSerial;
                message.dlrReturnMetadata = metadata();
            };
            var message = message(false);
            var originalSource = source(message);
            var execution = harness.submit(message);
            assertThat(policy.blocked.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(harness.provider.sends).hasValue(0);
            assertThat(harness.pending.find(originalSource)).isPresent();
            assertThat(policy.owner.usedCapacity()).isEqualTo(disabled ? 0 : 1);
            if (!disabled) {
                policy.owner.releaseOutbound(Set.of(unrelated));
            }
            policy.disabled.set(false);
            harness.provider.respond(harness.provider.next(), "filtered");
            execution.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(harness.pending.find(originalSource)).isEmpty();
            assertThat(harness.storage.getState(changedSerial)).isPresent();
            assertThat(policy.owner.usedCapacity()).isEqualTo(1);
            assertThat(harness.provider.sends).hasValue(1);
        }
    }

    @Test
    void failedHandoffRetainsOwnershipWithoutResend() throws Exception {
        var policy = new Policy(false);
        try (var harness = new Harness(policy)) {
            harness.failNextHandoff.set(true);
            var message = message(true);
            var execution = harness.submit(message);
            var request = harness.provider.next();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (execution.attempts().getFirst().state() != StandardOutboundDispatch.AttemptState.AWAITING_COMPLETION
                    && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(1);
            }
            assertThat(execution.attempts().getFirst().state()).isEqualTo(StandardOutboundDispatch.AttemptState.AWAITING_COMPLETION);
            harness.provider.respond(request, "accepted");
            assertThat(execution.completion().toCompletableFuture()).isNotDone();
            assertThat(harness.pending.find(source(message))).isPresent();
            assertThat(policy.owner.usedCapacity()).isEqualTo(1);
            execution.retryPendingHandoffs();
            execution.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(harness.provider.sends).hasValue(1);
            assertThat(harness.handoffs).hasValue(2);
            assertThat(harness.pending.find(source(message))).isEmpty();
        }
    }

    @Test
    void earlyReceiptDoesNotStrandLaterPartHandoff() throws Exception {
        var policy = new Policy(false);
        try (var harness = new Harness(policy)) {
            harness.provider.parts = 2;
            var message = message(true);
            var execution = harness.submit(message);
            var first = harness.provider.next();
            var second = harness.provider.next();
            harness.provider.respond(first, "first");
            harness.storage.resolveDlr("P", "first", MessageState.MessageStatus.DELIVERED, 1, "0").orElseThrow();
            harness.storage.claimDueHttpDeliveries(1);
            harness.storage.completeDelivery(message.serial, 1);
            assertThat(policy.owner.usedCapacity()).isEqualTo(1);
            assertThat(harness.pending.find(source(message))).isPresent();
            harness.provider.respond(second, "second");
            execution.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(harness.pending.find(source(message))).isEmpty();
            assertThat(harness.storage.getState(message.serial)).isEmpty();
            assertThat(harness.storage.resolveDlr("P", "second", MessageState.MessageStatus.FAILED, 2, "1")).isEmpty();
            assertThat(policy.owner.usedCapacity()).isZero();
            assertThat(harness.provider.sends).hasValue(2);
        }
    }

    @Test
    void replacementWithNewReceiptRequirementWaitsForCapacity() throws Exception {
        var policy = new Policy(false);
        try (var harness = new Harness(policy)) {
            harness.provider.replaceFirst = true;
            var preparations = new AtomicInteger();
            harness.provider.filter = message -> {
                if (preparations.incrementAndGet() > 1) {
                    message.dlrReturnMetadata = metadata();
                }
            };
            var execution = harness.submit(message(false));
            var first = harness.provider.next();
            var unrelated = source(message(true));
            policy.owner.reserveAdmission(unrelated, message(true));
            harness.provider.respond(first, "replaced");
            assertThat(policy.blocked.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(harness.provider.sends).hasValue(1);
            assertThat(policy.owner.usedCapacity()).isEqualTo(1);
            policy.owner.releaseOutbound(Set.of(unrelated));
            harness.provider.respond(harness.provider.next(), "replacement");
            execution.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(harness.provider.sends).hasValue(2);
            verify(harness.coordinator, times(1)).takeFromRouted(any(), any());
        }
    }

    private static final class Policy implements OutboundDlrPolicy {
        private final MemoryDlrReservations owner = new MemoryDlrReservations(1);
        private final AtomicBoolean disabled;
        private final CountDownLatch blocked = new CountDownLatch(1);

        private Policy(boolean disabled) {
            this.disabled = new AtomicBoolean(disabled);
        }

        @Override
        public Reservation reserveAdmission(SourceId source, StandardMessage message) {
            return owner.reserveAdmission(source, message);
        }

        @Override
        public void prepareProvider(String id, Set<SourceId> required) {
            try {
                if (disabled.get() && !required.isEmpty()) {
                    throw new Blocked(BlockReason.DISABLED, required.size(), 0);
                }
                owner.prepareProvider(id, required);
            } catch (Blocked failure) {
                blocked.countDown();
                throw failure;
            }
        }

        @Override
        public void releaseOutbound(Set<SourceId> sources) {
            owner.releaseOutbound(sources);
        }
    }

    private static final class Harness implements AutoCloseable {
        private final MemoryPendingMessageStore<StandardMessage> pending =
                new MemoryPendingMessageStore<>(10, MemoryDlrDispatchTest::copy);
        private final DefaultOutboundCoordinator<StandardMessage> coordinator;
        private final StandardOutboundDispatch dispatch;
        private final DlrStorage storage;
        private final Provider provider;
        private final AtomicBoolean failNextHandoff = new AtomicBoolean();
        private final AtomicInteger handoffs = new AtomicInteger();

        private Harness(Policy policy) throws ReflectiveOperationException {
            storage = memoryStorage(policy.owner);
            coordinator = spy(new DefaultOutboundCoordinator<>(pending, new MemorySelectedRouterStore<>(pending, 10),
                    new MemoryRoutedWorkStore<>(10, MemoryDlrDispatchTest::copy), 10, policy));
            coordinator.start();
            var service = new DlrService() {
                @Override
                public CompletionStage<Optional<MessageState>> recordProviderAcceptedAsync(MessageState state, String name, String id) {
                    handoffs.incrementAndGet();
                    if (failNextHandoff.getAndSet(false)) {
                        return CompletableFuture.failedStage(new DlrStorageException("testHandoff|error|reason:injected"));
                    }
                    try {
                        storage.recordProviderAccepted(state, name, id);
                        return CompletableFuture.completedStage(Optional.empty());
                    } catch (RuntimeException failure) {
                        return CompletableFuture.failedStage(failure);
                    }
                }
            };
            provider = spy(new Provider(service));
            doReturn("smpp.provider").when(provider).getFullName();
            dispatch = new StandardOutboundDispatch(coordinator, mock(StandardRoutingManager.class));
        }

        private StandardOutboundDispatch.ProviderExecution submit(StandardMessage message) throws Exception {
            coordinator.accept(source(message), message);
            coordinator.selectToRouter(10);
            var selected = coordinator.takeForRouting(Duration.ZERO).orElseThrow();
            coordinator.recordToRouted(selected, new Destination<>("smpp.provider", selected.message()));
            var work = dispatch.takeForProvider("smpp.provider", Duration.ZERO).orElseThrow();
            return dispatch.submitToProvider(work, provider);
        }

        @Override
        public void close() throws Exception {
            try {
                dispatch.close();
            } finally {
                provider.keepOnRunning = false;
                coordinator.close();
                ((AutoCloseable) storage).close();
            }
        }
    }

    /** Uses real preparation, request-part registration, response fencing and tracker handoffs; no network. */
    private static final class Provider extends SmppClientWorker<StandardMessage> {
        private final BlockingQueue<SubmitSm> sent = new LinkedBlockingQueue<>();
        private final AtomicInteger sends = new AtomicInteger();
        private Consumer<StandardMessage> filter = _ -> { };
        private boolean replaceFirst;
        private int parts = 1;
        private CoordinatedRetry<StandardMessage> retry;

        private Provider(DlrService service) {
            keepOnRunning = true;
            threadCount = 1;
            stats = new StatsKeeper();
            logger = LoggerFactory.getLogger(Provider.class);
            configurationProvider = mock(SendiumConfigurationProvider.class);
            workerResources = new WorkerResourceProvider() {
                @Override
                public boolean isDlrPersistenceEnabled() {
                    return true;
                }

                @Override
                public DlrService getDlrService() {
                    return service;
                }
            };
            messageTracker = new StandardMessageTracker(this);
        }

        @Override
        public String getDlrProviderName() {
            return "P";
        }

        @Override
        public boolean isPause() {
            return false;
        }

        @Override
        protected void applyRateLimit() {
        }

        @Override
        protected String doCharMap(StandardMessage message) {
            return message.body;
        }

        @Override
        protected void checkBeforeDoMessageFilters(StandardMessage message) {
            filter.accept(message);
        }

        @Override
        public CoordinatedSmppSubmission<StandardMessage> submitCoordinated(StandardMessage message,
                                                                            CoordinatedRetry<StandardMessage> retry,
                                                                            boolean replacement) {
            this.retry = retry;
            try {
                return super.submitCoordinated(message, retry, replacement);
            } catch (com.cloudhopper.smpp.type.SmppInvalidArgumentException failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public List<SubmitSm> generateSubmitRequest(StandardMessage message) {
            var requests = new ArrayList<SubmitSm>();
            for (int i = 0; i < parts; i++) {
                var request = new SubmitSm();
                request.setReferenceObject(copy(message));
                requests.add(request);
            }
            return requests;
        }

        @Override
        protected void sendCoordinatedRequest(SubmitSm request) {
            sends.incrementAndGet();
            sent.add(request);
        }

        @Override
        protected CompletionStage<Void> coordinatedProviderHandoff(StandardMessage message, int status, String id) {
            if (replaceFirst) {
                replaceFirst = false;
                return retry.retry(message, NackHandlePolicy.RETRY_WORKER);
            }
            return messageTracker.handoffProviderAccepted("", message, id);
        }

        private SubmitSm next() throws InterruptedException {
            var request = sent.poll(5, TimeUnit.SECONDS);
            assertThat(request).as("provider request").isNotNull();
            return request;
        }

        private void respond(SubmitSm request, String id) throws ReflectiveOperationException {
            // Production session handlers call this package-private response entry point.
            var response = SmppClientWorker.class.getDeclaredMethod("handleCoordinatedResponse", Object.class, int.class, String.class);
            response.setAccessible(true);
            response.invoke(this, request.getReferenceObject(), SmppConstants.STATUS_OK, id);
        }
    }

    private static DlrStorage memoryStorage(MemoryDlrReservations owner) throws ReflectiveOperationException {
        var constructor = Class.forName("gr.cytech.sendium.core.worker.MemoryDlrStorage").getDeclaredConstructor(
                MemoryDlrReservations.class, Duration.class, Duration.class, Duration.class, Clock.class);
        constructor.setAccessible(true);
        return (DlrStorage) constructor.newInstance(owner, Duration.ofDays(3), Duration.ofDays(7), Duration.ofMinutes(5),
                Clock.systemUTC());
    }

    private static StandardMessage message(boolean dlr) {
        var message = new StandardMessage();
        message.serial = UUID.randomUUID().toString();
        message.body = "synthetic";
        if (dlr) {
            message.dlrReturnMetadata = metadata();
        }
        return message;
    }

    private static DlrReturnMetadata metadata() {
        return DlrReturnMetadata.http("account", "sender", "recipient", "https://callback.test");
    }

    private static SourceId source(StandardMessage message) {
        return new SourceId(UUID.fromString(message.serial));
    }

    private static StandardMessage copy(StandardMessage message) {
        return PendingMessageData.capture(message).restore();
    }
}
