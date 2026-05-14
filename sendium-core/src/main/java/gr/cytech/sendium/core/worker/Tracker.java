package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;

import java.util.HashMap;

public interface Tracker<M extends StandardMessage> {

    void init();

    boolean stop();

    void configure(String key, String newValue, String oldValue);

    int updateSendStatusAndExtID(String smsid, M pMsg, String smscid);

    String getHashedMessageID(String messageId);

    String getVendorPriceGateway();

    void createAndEnqueueDLR(int mqid, String smscid, String smsid, String from, String to, String body,
                                    int state, String errorCode, HashMap<String, String> tlvs);

    int getConfiguredMccMnc();
}
