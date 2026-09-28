package gr.cytech.sendium.app.storage;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

class SmsStorageMemoryStartupTest {
    @RegisterExtension
    static final QuarkusExtensionTest APP = SmsStorageStartupSupport.application()
            .overrideRuntimeConfigKey(SmsStorageProfile.PENDING_BACKEND, "memory")
            .overrideRuntimeConfigKey(SmsStorageProfile.ROUTER_BACKEND, "memory")
            .overrideRuntimeConfigKey(SmsStorageProfile.ROUTED_BACKEND, "memory")
            .assertLogRecords(records -> assertThat(records).extracting(SmsStorageStartupSupport::message)
                    .containsExactly(
                            "Outbound SMS storage profile (pending/router-queue/routed-work): memory/memory/memory",
                            "NON-DURABLE outbound SMS storage: accepted messages and in-flight work can be lost on process restart.",
                            SmsStorageStartupSupport.ADMISSION_STARTED));

    @Inject
    SmsStorageProfile profile;

    @Inject
    SmsAdmissionProbe admission;

    @Test
    void validatedProfileIsAvailableBeforeAdmissionStartup() {
        assertThat(profile).isEqualTo(new SmsStorageProfile("memory", "memory", "memory"));
        assertThat(admission.profileAtStartup()).isSameAs(profile);
    }
}
