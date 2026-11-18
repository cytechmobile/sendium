package gr.cytech.sendium.core.storage.contract;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.storage.memory.MemoryPendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;

class MemorySelectedRouterStoreContractTest extends SelectedRouterStoreContract {
    @Override
    Stores createStores(int pendingCapacity, int selectedCapacity) {
        var pending = new MemoryPendingMessageStore<StandardMessage>(pendingCapacity,
                message -> PendingMessageData.capture(message).restore());
        return new Stores(pending, new MemorySelectedRouterStore<>(pending, selectedCapacity));
    }
}
