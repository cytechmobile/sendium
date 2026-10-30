package gr.cytech.sendium.app.storage;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.fail;

class MessageStoragePartialFileStartupTest {
    @RegisterExtension
    static final QuarkusExtensionTest APP = MessageStorageStartupSupport.rejected("memory", "memory", "file");

    @Test
    void startupMustFailBeforeAdmission() {
        fail("Unsupported profile started");
    }
}
