package gr.cytech.sendium.conf;

import com.google.common.base.Strings;
import gr.cytech.sendium.util.SensitiveLogSanitizer;
import io.quarkus.arc.DefaultBean;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@ApplicationScoped
@DefaultBean
public class SendiumConfigurationHandler implements SendiumConfigurationProvider {
    private static final Logger logger = LoggerFactory.getLogger(SendiumConfigurationHandler.class);

    public Map<String, String> defaultsConfiguration;
    public Map<String, String> overriddenDefaultsConfiguration;
    public Map<String, String> memoryConfiguration;
    public Map<String, Property> currentStoreConfiguration;
    public Set<PropertyChangeListener> listeners;

    public SendiumConfigurationHandler() {
        // CDI
    }

    @PostConstruct
    public void init() {
        memoryConfiguration = new ConcurrentHashMap<>();
        defaultsConfiguration = new ConcurrentHashMap<>();
        overriddenDefaultsConfiguration = new ConcurrentHashMap<>();
        currentStoreConfiguration = extractConfigProps();
        listeners = new CopyOnWriteArraySet<>();
    }

    public String get(String key) {
        try {
            return get(key, null);
        } catch (Exception e) {
            logger.debug("error getting config key: {}", key, e);
            return null;
        }
    }

    public String get(String key, String def) {
        String value = memoryConfiguration.get(key);
        if (value != null) {
            return value;
        }

        var prop = currentStoreConfiguration.get(key);
        if (prop != null && prop.value() != null) {
            return prop.value();
        }

        if (overriddenDefaultsConfiguration != null && overriddenDefaultsConfiguration.containsKey(key)) {
            return overriddenDefaultsConfiguration.get(key);
        }

        if (key != null && key.contains(".instance.")) {
            // Transform "outSms.instance.worker_name.property" into "outSms.default.property"
            // worker X will search for their outSms.instance.X.filters.afterDoMessageSuccess
            // When X asks for its filters, the handler translates the request and finds "outSms.default.filters.afterDoMessageSuccess"
            String defaultKey = key.replaceFirst("\\.instance\\.[^.]+\\.", ".default.");

            var defaultProp = currentStoreConfiguration.get(defaultKey);
            if (defaultProp != null && defaultProp.value() != null) {
                return defaultProp.value();
            }
            if (memoryConfiguration.containsKey(defaultKey)) {
                return memoryConfiguration.get(defaultKey);
            }
            if (overriddenDefaultsConfiguration != null && overriddenDefaultsConfiguration.containsKey(defaultKey)) {
                return overriddenDefaultsConfiguration.get(defaultKey);
            }
            if (defaultsConfiguration.containsKey(defaultKey)) {
                return defaultsConfiguration.get(defaultKey);
            }
        }

        return defaultsConfiguration.getOrDefault(key, def);
    }

    public String set(String key, String val) {
        if (val == null) {
            return remove(key);
        }
        try {
            return memoryConfiguration.put(key, val);
        } catch (Exception e) {
            logger.warn("error setting property {} to {}", key, SensitiveLogSanitizer.maskValue(key, val), e);
            return null;
        }
    }

    public String remove(String key) {
        try {
            overriddenDefaultsConfiguration.remove(key);
            return memoryConfiguration.remove(key);
        } catch (Exception e) {
            logger.warn("error removing key: {}", key, e);
        }

        return null;
    }

    protected void firePropertyChangeEvent(Map.Entry<String, String> entry) {
        firePropertyChangeEvent(entry.getKey(), getPrpt(entry.getKey()), entry.getValue());
    }

    public void firePropertyChangeEvent(String key, String val, String oldValue) {
        firePropertyChangeEvent(new PropertyChangeEvent(key, val, oldValue));
    }

    public void firePropertyChangeEvent(PropertyChangeEvent evt) {
        logger.info("firing property change for event: {}", evt);
        for (PropertyChangeListener l : listeners) {
            try {
                l.propertyChange(evt);
            } catch (Throwable e) {
                logger.warn("exception signaling property change event {} to listener:{}", evt, l, e);
            }
        }
    }

    public String setDefault(String key, String value) {
        if (value == null) {
            return remove(key);
        }
        return overriddenDefaultsConfiguration.put(key, value);
    }

    public boolean storeProperties(Map<String, String> props) {
        var prs = props.entrySet().stream().map(e -> Map.entry(e.getKey(),
                        new Property(e.getKey(), e.getValue(), null)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return storeProperties(prs, false);
    }

    public boolean storeProperties(Collection<Property> props, boolean replace) {
        return storeProperties(props.stream().collect(Collectors.toMap(Property::key, p -> p, (p1, p2) -> p1)), replace);
    }

    public boolean storeProperties(Map<String, Property> props, boolean replace) {
        if (props == null || props.isEmpty()) {
            return false;
        }
        for (var e : props.entrySet()) {
            if (e.getValue() == null || e.getValue().value() == null) {
                remove(e.getKey());
            } else {
                set(e.getKey(), e.getValue().value());
            }
        }
        return false;
    }

    public Set<String> getAllKeysReadOnly() {
        while (true) {
            try {
                Set<String> keys = new HashSet<>(defaultsConfiguration.keySet());
                keys.addAll(currentStoreConfiguration.keySet());
                keys.addAll(memoryConfiguration.keySet());
                return keys;
            } catch (Exception e) {
                logger.debug("caught exception copying all configuration keys, sleeping and retrying", e);
                try {
                    TimeUnit.MILLISECONDS.sleep(ThreadLocalRandom.current().nextInt(1, 100));
                } catch (Exception ex) {
                    logger.warn("exception caught while sleeping ", ex);
                }
            }
        }
    }

    public String setProperty(String key, String value) {
        return set(key, value);
    }

    public String setPropertyAndNotify(String key, String value) {
        String old = set(key, value);
        firePropertyChangeEvent(key, value, old);
        return old;
    }

    public void removeProperty(String key) {
        remove(key);
    }

    public static Map<String, Property> extractConfigProps() {
        final var conf = ConfigProvider.getConfig();
        final var props = conf.getPropertyNames();
        final Map<String, Property> result = new HashMap<>();
        for (var prop : props) {
            result.put(prop, new Property(prop, conf.getConfigValue(prop).getValue(), null));
        }
        return result;
    }

    public Map<String, Property> getCurrentStoreConfiguration() {
        return currentStoreConfiguration;
    }

    public String getPrpt(String prop) {
        return get(prop);
    }

    public String getPrpt(String prop, String def) {
        return get(prop, def);
    }

    public String getPrpt(String[] props) {
        return get(props[0], props[1]);
    }

    public boolean getBlnPrpt(String prop) {
        return "true".equalsIgnoreCase(getPrpt(prop));
    }

    public boolean getBlnPrpt(String prop, String def) {
        String prpt = get(prop, def);
        return "true".equalsIgnoreCase(prpt);
    }

    public boolean getBlnPrpt(String[] props) {
        return getBlnPrpt(props[0], props[1]);
    }

    public boolean getBlnPrpt(String prop, boolean def) {
        String prpt = get(prop, null);
        return prpt != null ? "true".equalsIgnoreCase(prpt) : def;
    }

    public int getIntPrpt(String prop) {
        return getIntPrpt(prop, -1);
    }

    public int getIntPrpt(String prop, int def) {
        try {
            String prpt = get(prop);
            return prpt != null ? Integer.parseInt(get(prop)) : def;
        } catch (Exception e) {
            return def;
        }
    }

    public int getIntPrpt(String prop, String def) {
        try {
            String prpt = get(prop, def);
            return prpt != null ? Integer.parseInt(prpt) : -1;
        } catch (Exception e) {
            try {
                return Integer.parseInt(def);
            } catch (Exception ex) {
                return -1;
            }
        }
    }

    public int getIntPrpt(String[] props) {
        return getIntPrpt(props[0], props[1]);
    }

    public long getLongPrpt(String prop) {
        return getLongPrpt(prop, -1);
    }

    public long getLongPrpt(String prop, long def) {
        try {
            String prpt = get(prop);
            return prpt != null ? Long.parseLong(prpt) : def;
        } catch (Exception e) {
            return def;
        }
    }

    public long getLongPrpt(String prop, String def) {
        try {
            String prpt = get(prop, def);
            return prpt != null ? Long.parseLong(prpt) : -1;
        } catch (Exception e) {
            try {
                return Long.parseLong(def);
            } catch (Exception ex) {
                return -1;
            }
        }
    }

    public long getLongPrpt(String[] props) {
        if (props == null || props.length < 2) {
            return -1;
        }
        return getLongPrpt(props[0], props[1]);
    }

    public void loadDefaultParams(String[][] prms) {
        loadDefaultParams(null, prms);
    }

    public void loadDefaultParams(String prefix, String[][] prms) {
        boolean hasPrefix = !Strings.isNullOrEmpty(prefix);
        if (hasPrefix && !prefix.endsWith(".")) {
            prefix = prefix + ".";
        }
        for (String[] prm : prms) {
            if (hasPrefix && !prm[0].startsWith(prefix)) {
                prm[0] = prefix + prm[0];
            }
            defaultsConfiguration.computeIfAbsent(prm[0], key -> System.getProperty(prm[0], System.getenv().getOrDefault(prm[0], prm[1])));
        }
    }

    public void addPropertyChangeListener(PropertyChangeListener el) {
        listeners.add(el);
    }

    public void removePropertyChangeListener(PropertyChangeListener el) {
        listeners.remove(el);
    }
}
