package gr.cytech.sendium.app.storage;

import io.quarkus.test.QuarkusExtensionTest;
import org.jboss.logmanager.ExtLogRecord;

import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;

final class SmsStorageStartupSupport {
    static final String ADMISSION_STARTED = "test admission startup reached";

    private SmsStorageStartupSupport() {
    }

    static QuarkusExtensionTest application() {
        return new QuarkusExtensionTest()
                .withApplicationRoot(archive -> archive.addClasses(
                        StandaloneSmsStorage.class, SmsStorageProfile.class, SmsAdmissionProbe.class,
                        SmsStorageStartupSupport.class))
                // Exercise the standalone assembly independently of protocol worker beans.
                .overrideConfigKey("quarkus.arc.exclude-dependency.core.group-id", "gr.cytech")
                .overrideConfigKey("quarkus.arc.exclude-dependency.core.artifact-id", "sendium-core")
                .overrideConfigKey("sendium.dlr.persistence.enabled", "false")
                .overrideConfigKey("quarkus.http.test-port", "0")
                // These assertions must remain valid when CI sets the root log level to ERROR.
                .overrideConfigKey("quarkus.log.category.\"gr.cytech.sendium.app.storage.StandaloneSmsStorage\".level", "INFO")
                .overrideConfigKey("quarkus.log.category.\"gr.cytech.sendium.app.storage.SmsAdmissionProbe\".level", "INFO")
                .setLogRecordPredicate(record -> StandaloneSmsStorage.class.getName().equals(record.getLoggerName()) ||
                        SmsAdmissionProbe.class.getName().equals(record.getLoggerName()));
    }

    static QuarkusExtensionTest rejected(String pending, String router, String routed) {
        return application()
                .overrideRuntimeConfigKey(SmsStorageProfile.PENDING_BACKEND, pending)
                .overrideRuntimeConfigKey(SmsStorageProfile.ROUTER_BACKEND, router)
                .overrideRuntimeConfigKey(SmsStorageProfile.ROUTED_BACKEND, routed)
                .assertException(failure -> assertThat(failure)
                        .hasStackTraceContaining("Unsupported outbound SMS storage profile '" +
                                pending + "/" + router + "/" + routed + "'")
                        .hasStackTraceContaining("Supported profiles: memory/memory/memory"))
                .assertLogRecords(records -> assertThat(records).isEmpty());
    }

    static String message(LogRecord record) {
        return record instanceof ExtLogRecord extended ? extended.getFormattedMessage() : record.getMessage();
    }
}
