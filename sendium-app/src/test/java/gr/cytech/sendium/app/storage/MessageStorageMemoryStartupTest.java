package gr.cytech.sendium.app.storage;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

class MessageStorageMemoryStartupTest {
    @RegisterExtension
    static final QuarkusExtensionTest APP = MessageStorageStartupSupport.application()
            .overrideRuntimeConfigKey(MessageStorageProfile.PENDING_BACKEND, "memory")
            .overrideRuntimeConfigKey(MessageStorageProfile.ROUTER_BACKEND, "memory")
            .overrideRuntimeConfigKey(MessageStorageProfile.ROUTED_BACKEND, "memory")
            .assertLogRecords(records -> assertThat(records).extracting(MessageStorageStartupSupport::message)
                    .containsExactly(
                            "Outbound message storage profile (pending/router-queue/routed-work): memory/memory/memory",
                            "NON-DURABLE outbound message storage: accepted messages and in-flight work can be lost on process restart.",
                            MessageStorageStartupSupport.ADMISSION_STARTED));

    @Inject
    MessageStorageProfile profile;

    @Inject
    MessageAdmissionProbe admission;

    @Test
    void validatedProfileIsAvailableBeforeAdmissionStartup() {
        assertThat(profile).isEqualTo(new MessageStorageProfile("memory", "memory", "memory"));
        assertThat(admission.profileAtStartup()).isSameAs(profile);
    }
}
