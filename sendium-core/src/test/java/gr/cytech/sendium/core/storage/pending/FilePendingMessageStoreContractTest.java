package gr.cytech.sendium.core.storage.pending;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.storage.PendingMessageStore;
import gr.cytech.sendium.core.storage.file.FilePendingMessageStore;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;

class FilePendingMessageStoreContractTest extends PendingMessageStoreContract {
    @TempDir
    Path root;
    private int sequence;

    @Override
    PendingMessageStore<StandardMessage> createStore(int capacity) {
        return createStore(capacity, Clock.systemUTC());
    }

    @Override
    PendingMessageStore<StandardMessage> createStore(int capacity, Clock clock) {
        return new FilePendingMessageStore(root.resolve(Integer.toString(sequence++)), capacity, clock);
    }

    @Override
    String expectedBackend() {
        return "file";
    }

    @Override
    boolean expectedDurability() {
        return true;
    }
}
