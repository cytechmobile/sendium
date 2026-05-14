package gr.cytech.sendium.core.message;

import java.util.Map;

public interface CoreMessage {
    public Map<String, Integer> getFieldMap();

    public String getValue(int pidx) throws IllegalArgumentException;

    public int getIntValue(int pidx) throws IllegalArgumentException;

    public boolean getBooleanValue(int pidx) throws IllegalArgumentException;

    public long getLongValue(int pidx) throws IllegalArgumentException;

    public float getFloatValue(int pidx) throws IllegalArgumentException;

    public short getShortValue(int pidx) throws IllegalArgumentException;

    public char getCharValue(int pidx) throws IllegalArgumentException;

    public byte getByteValue(int pidx) throws IllegalArgumentException;
}
