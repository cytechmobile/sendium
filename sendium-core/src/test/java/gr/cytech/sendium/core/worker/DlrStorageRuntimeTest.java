package gr.cytech.sendium.core.worker;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class DlrStorageRuntimeTest {
    @Inject
    Instance<DlrStorage> storageInstance;

    @Inject
    ConfiguredDlrStorage configuredStorage;

    @Inject
    MeterRegistry meterRegistry;

    @Test
    void selectsExactlyOneMvStoreBackendInTestProfile() {
        assertThat(storageInstance.isResolvable()).isTrue();
        assertThat(storageInstance.stream()).hasSize(1);
        assertThat(storageInstance.get()).isSameAs(configuredStorage);
        assertThat(configuredStorage.backend()).isEqualTo("mvstore");
    }

    @Test
    void exposesReadinessAndStorageMetrics() {
        configuredStorage.getState(UUID.randomUUID().toString());

        given()
                .when().get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.find { it.name == 'sendium-dlr-storage' }.data.backend", equalTo("mvstore"));

        given()
                .when().get("/q/metrics")
                .then()
                .statusCode(200)
                .body(containsString("sendium_dlr_storage_selected"))
                .body(containsString("sendium_dlr_storage_operation_seconds_count"));

        assertThat(meterRegistry.find("sendium.dlr.storage.operation")
                .tag("operation", "get_state").timer().count()).isGreaterThanOrEqualTo(1);
    }
}
