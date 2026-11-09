package gr.cytech.sendium.app.storage;

import io.quarkus.test.QuarkusExtensionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class FilePendingMissingPathStartupTest {
    @RegisterExtension
    static final QuarkusExtensionTest APP = new QuarkusExtensionTest()
            .withApplicationRoot(archive -> archive.addClasses(StandaloneOutboundPipeline.class,
                    StandaloneOutboundPipeline.LifecycleRouting.class, StandaloneMessageSnapshot.class,
                    StandaloneMessageStorage.class, MessageStorageProfile.class,
                    StandaloneOutboundPipelineTest.Beans.class, StandaloneOutboundPipelineTest.Providers.class,
                    MessageLifecycleHttpTest.Credentials.class))
            .overrideConfigKey("quarkus.arc.exclude-dependency.core.group-id", "gr.cytech")
            .overrideConfigKey("quarkus.arc.exclude-dependency.core.artifact-id", "sendium-core")
            .overrideConfigKey("sendium.dlr.persistence.enabled", "false")
            .overrideConfigKey(MessageStorageProfile.PENDING_BACKEND, "file")
            .overrideConfigKey(MessageStorageProfile.PENDING_PATH, " ")
            .overrideConfigKey("quarkus.http.test-port", "0")
            .assertException(failure -> assertThat(failure).hasStackTraceContaining("reason:missingPendingPath"));

    @Test
    void missingSpoolPreventsApplicationStartup() {
        fail("File pending started without its required spool path");
    }
}
