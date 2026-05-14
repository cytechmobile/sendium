package gr.cytech.sendium.conf;

import java.util.Map;
import java.util.Set;

public interface SendiumConfigurationProvider {

    long getLongPrpt(String[] props);

    long getLongPrpt(String prop, long def);

    String getPrpt(String[] props);

    String getPrpt(String prop);

    String getPrpt(String property, String defaultValue);

    int getIntPrpt(String[] props);

    int getIntPrpt(String s, int intPrpt);

    boolean getBlnPrpt(String[] props);

    boolean getBlnPrpt(String s, boolean defaultValue);

    void loadDefaultParams(String[][] prms);

    void loadDefaultParams(String prefix, String[][] prms);

    boolean storeProperties(Map<String, String> props);

    void addPropertyChangeListener(PropertyChangeListener propertyChanged);

    void removePropertyChangeListener(PropertyChangeListener propertyChangeListener);

    Set<String> getAllKeysReadOnly();

    String setProperty(String s, String aFalse);
}
