package gr.cytech.sendium.app.storage;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

class MessageStorageFileStartupTest {
    @RegisterExtension
    static final QuarkusExtensionTest APP = MessageStorageStartupSupport.application()
            .overrideRuntimeConfigKey(MessageStorageProfile.PENDING_BACKEND, "file")
            .overrideRuntimeConfigKey(MessageStorageProfile.ROUTER_BACKEND, "file")
            .overrideRuntimeConfigKey(MessageStorageProfile.ROUTED_BACKEND, "memory")
            .assertLogRecords(records -> assertThat(records).extracting(MessageStorageStartupSupport::message)
                    .containsExactly("messageStorage|configured|profile:file/file/memory",
                            "messageStorage|durabilityLimited|pending:processRestart|selection:restoredAfterRestart|routing:repeatedAfterRestart" +
                                    "|powerLoss:notGuaranteed|exactlyOnce:notGuaranteed",
                            MessageStorageStartupSupport.ADMISSION_STARTED));

    @Inject MessageStorageProfile profile;
    @Inject MessageAdmissionProbe admission;

    @Test
    void durableSelectionProfileIsValidatedBeforeAdmissionStartup() {
        assertThat(profile).isEqualTo(new MessageStorageProfile("file", "file", "memory"));
        assertThat(admission.profileAtStartup()).isSameAs(profile);
    }
}
