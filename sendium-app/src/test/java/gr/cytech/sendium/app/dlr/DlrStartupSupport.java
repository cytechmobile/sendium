package gr.cytech.sendium.app.dlr;

import gr.cytech.sendium.auth.CredentialFileWatcher;
import gr.cytech.sendium.core.worker.DlrMessageStorage;
import gr.cytech.sendium.core.worker.DlrService;
import gr.cytech.sendium.core.worker.DlrStorage;
import gr.cytech.sendium.core.worker.DlrStorageMetrics;
import gr.cytech.sendium.core.worker.DlrStorageReadinessCheck;
import gr.cytech.sendium.core.worker.ForwardDlrService;
import gr.cytech.sendium.core.worker.ManagedDlrStorage;
import gr.cytech.sendium.core.worker.ProviderDlrProcessor;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

final class DlrStartupSupport {
    private DlrStartupSupport() {
    }

    static QuarkusExtensionTest application() {
        return new QuarkusExtensionTest()
                .withApplicationRoot(archive -> archive.addClasses(
                        DlrMessageStorage.class, DlrStorage.class, ManagedDlrStorage.class,
                        DlrService.class, ProviderDlrProcessor.class, ForwardDlrService.class,
                        DlrStorageReadinessCheck.class, DlrStorageMetrics.class,
                        DlrStartupSupport.class, EmptyCredentials.class))
                // Isolate unrelated protocol workers, but include the real conditional DLR beans above.
                .overrideConfigKey("quarkus.arc.exclude-dependency.core.group-id", "gr.cytech")
                .overrideConfigKey("quarkus.arc.exclude-dependency.core.artifact-id", "sendium-core")
                .overrideConfigKey("sendium.dlr.persistence.enabled", "false")
                .overrideConfigKey("quarkus.http.test-port", "0");
    }

    /** REST discovers the native authentication filter even in this isolated fixture. */
    @Singleton
    public static class EmptyCredentials {
        @Produces
        @Singleton
        CredentialFileWatcher credentials() {
            return new CredentialFileWatcher();
        }
    }
}
