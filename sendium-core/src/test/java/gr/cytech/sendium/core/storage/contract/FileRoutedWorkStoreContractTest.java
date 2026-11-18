package gr.cytech.sendium.core.storage.contract;

import gr.cytech.sendium.core.storage.file.FilePendingMessageStore;
import gr.cytech.sendium.core.storage.file.FileRoutedWorkStore;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class FileRoutedWorkStoreContractTest extends RoutedWorkStoreContract {
    @TempDir Path root;

    @Override
    Stores createStores(int pendingCapacity, int routedCapacity) {
        var pending = new FilePendingMessageStore(root, pendingCapacity);
        return new Stores(pending, new FileRoutedWorkStore(pending, routedCapacity));
    }
}
