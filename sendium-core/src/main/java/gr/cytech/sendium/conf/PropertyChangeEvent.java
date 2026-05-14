package gr.cytech.sendium.conf;

import java.util.EventObject;

public class PropertyChangeEvent extends EventObject {
    public String key;
    public String value;
    public String oldValue;

    public PropertyChangeEvent(String propertyName, String propertyValue) {
        this(propertyName, propertyValue, null);
    }

    public PropertyChangeEvent(String propertyName, String propertyValue, String oldValue) {
        super(propertyName);
        this.key = propertyName;
        this.value = propertyValue;
        this.oldValue = oldValue;
    }

    public String getKey() {
        return this.key;
    }

    public String getValue() {
        return this.value;
    }

    public String getNewValue() {
        return this.value;
    }

    public String getOldValue() {
        return this.oldValue;
    }

    @Override
    public String toString() {
        return "{\"key\":\"" + key + "\"," +
                "\"value\":\"" + value + "\"," +
                "\"oldValue\":\"" + oldValue + "\"}";
    }
}