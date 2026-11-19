package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Public test fixture for outbound-package tests: the real provider-event processor and managed accounting over a
 * file store, producing the same required handoff stages as the standard tracker.
 */
public final class FileDlrHandoffFixture implements AutoCloseable {
    private final FileDlrStorage storage;
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ProviderDlrProcessor processor;

    private FileDlrHandoffFixture(Path directory) throws ReflectiveOperationException {
        storage = FileDlrStorage.open(directory);
        var managed = new ManagedDlrStorage();
        managed.meterRegistry = metrics;
        Field delegate = ManagedDlrStorage.class.getDeclaredField("delegate");
        delegate.setAccessible(true);
        delegate.set(managed, storage);
        processor = new ProviderDlrProcessor(managed, metrics, 10, Duration.ofMillis(20), 100, Duration.ofSeconds(5));
    }

    public static FileDlrHandoffFixture open(Path directory) {
        try {
            return new FileDlrHandoffFixture(directory);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every later commit fails with an uncertain outcome, latching the store unavailable. */
    public void failPublication() {
        storage.beforeCommit = c -> c.close();
    }

    public CompletionStage<Void> accepted(String gatewayId, String providerMessageId) {
        return processor.submit(new ProviderDlrEvent.Accepted(state(gatewayId), "P", providerMessageId))
                .thenApply(ignored -> null);
    }

    public CompletionStage<Void> rejected(String gatewayId, String providerMessageId) {
        return processor.submit(new ProviderDlrEvent.Rejected(state(gatewayId), "P", providerMessageId,
                StandardMessage.DLR_STAT_REJECTD, "11")).thenApply(ignored -> null);
    }

    public Optional<String> deliveryStatus(String gatewayId) {
        return storage.getState(gatewayId).map(state -> state.getDeliveryStatus().name());
    }

    public Optional<String> correlationOwner(String providerMessageId) {
        return storage.correlationOwner("P", providerMessageId);
    }

    @Override
    public void close() {
        processor.close();
        storage.close();
        metrics.close();
    }

    private static MessageState state(String gatewayId) {
        MessageState state = new MessageState(gatewayId, "account", "system", "from", "to",
                "https://callback.test/dlr");
        state.setDeliveryChannel(MessageState.DeliveryChannel.HTTP);
        return state;
    }
}
