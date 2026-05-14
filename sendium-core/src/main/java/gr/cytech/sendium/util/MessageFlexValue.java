package gr.cytech.sendium.util;

import gr.cytech.sendium.core.message.StandardMessage;

public class MessageFlexValue {
    private final String prmVal;
    private final int msgFldIdx;

    public MessageFlexValue(String defVal) {
        prmVal = defVal;
        msgFldIdx = getMessageFieldIndexFor(defVal);
    }

    public String getValueFor(StandardMessage pMsg) {
        String result = msgFldIdx == 0 ? prmVal : pMsg.getValue(msgFldIdx);
        if (result == null || result.isEmpty() || result.equals("null")) {
            return null;
        }
        return result;
    }

    private int getMessageFieldIndexFor(String potentialFieldName) {
        if (potentialFieldName == null || potentialFieldName.isEmpty()) {
            return 0;
        }
        if (potentialFieldName.startsWith("MESSAGE:")) {
            Integer fieldIndex = StandardMessage.fieldMap.get(potentialFieldName.substring(8));
            if (fieldIndex != null) {
                return fieldIndex;
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        MessageFlexValue mfv = (MessageFlexValue) o;

        return prmVal.equals(mfv.prmVal);
    }

    @Override
    public int hashCode() {
        return prmVal.hashCode();
    }

    @Override
    public String toString() {
        return "MessageFlexValue{" +
                "prmVal='" + prmVal + '\'' +
                ", msgFldIdx=" + msgFldIdx +
                '}';
    }
}