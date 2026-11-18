package gr.cytech.sendium.core.storage.contract;

import gr.cytech.sendium.core.storage.file.FilePendingMessageStore;
import gr.cytech.sendium.core.storage.file.FileSelectedRouterStore;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class FileSelectedRouterStoreContractTest extends SelectedRouterStoreContract {
    @TempDir Path root;

    @Override
    Stores createStores(int pendingCapacity, int selectedCapacity) {
        var pending = new FilePendingMessageStore(root, pendingCapacity);
        return new Stores(pending, new FileSelectedRouterStore(pending, selectedCapacity));
    }
}
