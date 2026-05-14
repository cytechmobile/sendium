package gr.cytech.sendium.core.smpp.server;

import com.cloudhopper.smpp.type.SmppProcessingException;

public interface SmppAuthenticationProvider {
    SmppSessionContext authenticate(String systemId, String password, String ipAddress) throws SmppProcessingException;
}
