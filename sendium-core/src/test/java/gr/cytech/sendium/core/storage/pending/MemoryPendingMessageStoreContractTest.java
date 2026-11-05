package gr.cytech.sendium.core.storage.pending;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.storage.PendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemoryPendingMessageStore;

import java.time.Clock;
import java.time.Instant;

class MemoryPendingMessageStoreContractTest extends PendingMessageStoreContract {
    @Override
    String expectedBackend() {
        return "memory";
    }

    @Override
    boolean expectedDurability() {
        return false;
    }

    @Override
    PendingMessageStore<StandardMessage> createStore(int capacity) {
        return createStore(capacity, Clock.systemUTC());
    }

    @Override
    PendingMessageStore<StandardMessage> createStore(int capacity, Clock clock) {
        return new MemoryPendingMessageStore<>(capacity, message -> {
            try {
                return (StandardMessage) message.clone();
            } catch (CloneNotSupportedException failure) {
                throw new AssertionError(failure);
            }
        }, message -> Instant.MIN, clock);
    }
}
