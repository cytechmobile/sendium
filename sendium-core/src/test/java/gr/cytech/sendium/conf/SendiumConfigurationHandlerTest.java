package gr.cytech.sendium.conf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import static org.assertj.core.api.Assertions.assertThat;

class SendiumConfigurationHandlerTest {

    private SendiumConfigurationHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SendiumConfigurationHandler();
        handler.memoryConfiguration = new ConcurrentHashMap<>();
        handler.defaultsConfiguration = new ConcurrentHashMap<>();
        handler.overriddenDefaultsConfiguration = new ConcurrentHashMap<>();
        handler.currentStoreConfiguration = new ConcurrentHashMap<>();
        handler.listeners = new CopyOnWriteArraySet<>();
    }

    @Test
    void getUsesMemoryBeforeStoreAndDefaults() {
        handler.defaultsConfiguration.put("conf.key", "default");
        handler.currentStoreConfiguration.put("conf.key", new Property("conf.key", "store", null));
        handler.set("conf.key", "memory");

        assertThat(handler.get("conf.key")).isEqualTo("memory");
    }

    @Test
    void getFallsBackFromInstanceKeyToDefaultKey() {
        handler.defaultsConfiguration.put("outSms.default.tps", "25");

        assertThat(handler.get("outSms.instance.worker1.tps")).isEqualTo("25");
    }

    @Test
    void setDefaultOverridesLoadedDefaultAndRemoveClearsOverride() {
        handler.defaultsConfiguration.put("conf.key", "default");

        handler.setDefault("conf.key", "override");
        assertThat(handler.get("conf.key")).isEqualTo("override");

        handler.remove("conf.key");
        assertThat(handler.get("conf.key")).isEqualTo("default");
    }

    @Test
    void typedGettersReturnFallbackWhenParsingFails() {
        handler.set("bad.int", "not-int");
        handler.set("bad.long", "not-long");

        assertThat(handler.getIntPrpt("bad.int", 7)).isEqualTo(7);
        assertThat(handler.getLongPrpt("bad.long", 9L)).isEqualTo(9L);
        assertThat(handler.getBlnPrpt("missing.bool", true)).isTrue();
    }

    @Test
    void setPropertyAndNotifySendsOldAndNewValues() {
        var events = new ArrayList<PropertyChangeEvent>();
        handler.set("conf.key", "old");
        handler.addPropertyChangeListener(events::add);

        String previous = handler.setPropertyAndNotify("conf.key", "new");

        assertThat(previous).isEqualTo("old");
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getKey()).isEqualTo("conf.key");
        assertThat(events.getFirst().getNewValue()).isEqualTo("new");
        assertThat(events.getFirst().getOldValue()).isEqualTo("old");
    }

    @Test
    void firePropertyChangeContinuesWhenOneListenerThrows() {
        var events = new ArrayList<PropertyChangeEvent>();
        handler.addPropertyChangeListener(evt -> {
            throw new RuntimeException("boom");
        });
        handler.addPropertyChangeListener(events::add);

        handler.firePropertyChangeEvent("conf.key", "new", "old");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getOldValue()).isEqualTo("old");
    }

    @Test
    void loadDefaultParamsPrefixesKeysOnlyOnce() {
        String[][] params = {{"host", "localhost"}, {"outSms.instance.worker.port", "2775"}};

        handler.loadDefaultParams("outSms.instance.worker", params);

        assertThat(handler.defaultsConfiguration)
                .containsEntry("outSms.instance.worker.host", "localhost")
                .containsEntry("outSms.instance.worker.port", "2775");
    }

    @Test
    void storePropertiesWritesValuesAndRemovesNullValues() {
        handler.set("conf.old", "value");

        boolean result = handler.storeProperties(Map.of(
                "conf.new", new Property("conf.new", "new-value", null),
                "conf.old", new Property("conf.old", null, null)), false);

        assertThat(result).isFalse();
        assertThat(handler.get("conf.new")).isEqualTo("new-value");
        assertThat(handler.get("conf.old")).isNull();
    }
}
