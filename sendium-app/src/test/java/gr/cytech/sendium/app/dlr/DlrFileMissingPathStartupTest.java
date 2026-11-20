package gr.cytech.sendium.app.dlr;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

class DlrFileMissingPathStartupTest {
    @RegisterExtension
    static final QuarkusExtensionTest APP = DlrStartupSupport.application()
            .overrideConfigKey("sendium.dlr.persistence.enabled", "true")
            .overrideConfigKey("sendium.dlr.storage.backend", "file")
            .assertException(failure -> assertThat(failure)
                    .hasStackTraceContaining("dlrStorage|error|backend:file|reason:missingPath"));

    @Test
    void fileSelectionWithoutPathFailsStartup() {
        throw new AssertionError("Startup must fail before admission");
    }
}
