package gr.cytech.sendium.core.storage.contract;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.storage.memory.MemoryPendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemoryRoutedWorkStore;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;

class MemoryRoutedWorkStoreContractTest extends RoutedWorkStoreContract {
    @Override
    Stores createStores(int pendingCapacity, int routedCapacity) {
        var pending = new MemoryPendingMessageStore<StandardMessage>(pendingCapacity,
                message -> PendingMessageData.capture(message).restore());
        var routed = new MemoryRoutedWorkStore<StandardMessage>(routedCapacity,
                message -> PendingMessageData.capture(message).restore());
        return new Stores(pending, routed);
    }
}
