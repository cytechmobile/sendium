package gr.cytech.sendium.app.storage;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.DefaultOutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundDlrPolicy;
import gr.cytech.sendium.core.storage.RoutedWorkStore;
import gr.cytech.sendium.core.storage.memory.MemoryPendingMessageStore;
import gr.cytech.sendium.core.storage.memory.MemorySelectedRouterStore;
import gr.cytech.sendium.core.worker.ManagedDlrStorage;
import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.arc.InjectableInstance;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StandaloneDlrPolicyWiringTest {
    @ParameterizedTest
    @ValueSource(strings = {"memory", "disabled"})
    @SuppressWarnings("unchecked")
    void selectedPolicyCannotBeBypassed(String backend) throws Exception {
        var managed = managed(backend);
        var metrics = new SimpleMeterRegistry();
        var storages = mock(Instance.class);
        when(storages.get()).thenReturn(managed);
        var config = mock(Config.class);
        when(config.getOptionalValue("sendium.dlr.storage.backend", String.class)).thenReturn(Optional.of(backend));
        when(config.getOptionalValue("sendium.dlr.persistence.enabled", Boolean.class)).thenReturn(Optional.of(true));
        try {
            var factory = StandaloneOutboundPipeline.class.getDeclaredMethod("coordinator",
                    MessageStorageProfile.class, Config.class, MeterRegistry.class, Instance.class);
            factory.setAccessible(true);
            try (var correct = (OutboundCoordinator<StandardMessage>) factory.invoke(null,
                    new MessageStorageProfile("memory", "memory", "memory"), config, metrics, storages)) {
                var policy = policy(managed);
                assertThat(enforces(correct, policy)).isTrue();
                assertThat(enforces(correct, OutboundDlrPolicy.UNRESTRICTED)).isFalse();
                var pipeline = new StandaloneOutboundPipeline();
                pipeline.config = config;
                var selectedStorages = StandaloneOutboundPipeline.class.getDeclaredField("dlrStorages");
                selectedStorages.setAccessible(true);
                selectedStorages.set(pipeline, storages);
                pipeline.coordinator = mock(OutboundCoordinator.class);
                assertThatThrownBy(() -> pipeline.start(null)).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("selectedPolicyNotEnforced");
                var pending = new MemoryPendingMessageStore<StandardMessage>(4, StandaloneMessageSnapshot::copy);
                var unsupported = new DefaultOutboundCoordinator<>(pending, new MemorySelectedRouterStore<>(pending, 4),
                        mock(RoutedWorkStore.class), 4, policy);
                assertThat(enforces(unsupported, policy)).isFalse();
            }
        } finally {
            close(managed);
            metrics.close();
        }
    }

    static OutboundDlrPolicy policy(ManagedDlrStorage storage) {
        try {
            return (OutboundDlrPolicy) ManagedDlrStorage.class.getMethod("outboundDlrPolicy").invoke(storage);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Selected managed mode must expose its exact policy", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static ManagedDlrStorage managed(String backend) throws Exception {
        var managed = new ManagedDlrStorage();
        set(managed, "selectedBackend", backend);
        set(managed, "deliveryClaimDuration", Duration.ofMinutes(5));
        set(managed, "meterRegistry", new SimpleMeterRegistry());
        InjectableInstance<AgroalDataSource> datasource = mock(InjectableInstance.class, RETURNS_DEEP_STUBS);
        when(datasource.getHandle().getBean().isActive()).thenReturn(false);
        set(managed, "postgresqlDataSource", datasource);
        var initialize = ManagedDlrStorage.class.getDeclaredMethod("initialize");
        initialize.setAccessible(true);
        try {
            initialize.invoke(managed);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw failure;
        }
        return managed;
    }

    private static void set(ManagedDlrStorage storage, String name, Object value) throws ReflectiveOperationException {
        var field = ManagedDlrStorage.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(storage, value);
    }

    private static boolean enforces(OutboundCoordinator<?> coordinator, OutboundDlrPolicy policy) throws Exception {
        return (boolean) OutboundCoordinator.class.getMethod("enforcesDlrPolicy", OutboundDlrPolicy.class).invoke(coordinator, policy);
    }

    private static void close(ManagedDlrStorage storage) throws Exception {
        var method = ManagedDlrStorage.class.getDeclaredMethod("close");
        method.setAccessible(true);
        method.invoke(storage);
    }
}
