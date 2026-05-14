package gr.cytech.sendium.core.smpp;

import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionListener;

public interface SmsgSmppSessionHandler extends SmppSessionListener {
    SmppSession getSession();

    void configMaxRate(String accountId);

    double getRate();

    long getLastPduTimestamp();

    boolean isBackupConnection();
}
