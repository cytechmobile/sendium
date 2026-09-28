package gr.cytech.sendium.app.storage;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.fail;

class SmsStorageFileStartupTest {
    @RegisterExtension
    static final QuarkusExtensionTest APP = SmsStorageStartupSupport.rejected("file", "file", "memory");

    @Test
    void startupMustFailBeforeAdmission() {
        fail("Unsupported profile started");
    }
}
