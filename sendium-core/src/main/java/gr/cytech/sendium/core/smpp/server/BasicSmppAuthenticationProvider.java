package gr.cytech.sendium.core.smpp.server;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.type.SmppProcessingException;
import gr.cytech.sendium.auth.CredentialFileWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;

public class BasicSmppAuthenticationProvider implements SmppAuthenticationProvider {
    private static final Logger logger = LoggerFactory.getLogger(BasicSmppAuthenticationProvider.class);
    private final SmppServerWorker worker;

    public BasicSmppAuthenticationProvider(SmppServerWorker worker) {
        this.worker = worker;
    }

    @Override
    public SmppSessionContext authenticate(String systemId, String password, String ipAddress) throws SmppProcessingException {
        if (systemId == null || password == null) {
            logger.warn("SMPP Bind failed: Missing systemId or password from IP [{}]", ipAddress);
            throw new SmppProcessingException(SmppConstants.STATUS_BINDFAIL);
        }

        Map<String, CredentialFileWatcher.Credential> credentials = worker.getWorkerResources().getCredentialFileWatcher().getValidCredentials();
        CredentialFileWatcher.Credential cred = credentials.get(systemId);

        if (cred == null || cred.type() != CredentialFileWatcher.CredentialType.SMPP) {
            logger.warn("SMPP Bind failed: Invalid systemId '{}' from IP [{}]", systemId, ipAddress);
            throw new SmppProcessingException(SmppConstants.STATUS_INVSYSID);
        }

        if (!password.equals(cred.password())) {
            logger.warn("SMPP Bind failed: Invalid password for systemId '{}' from IP [{}]", systemId, ipAddress);
            throw new SmppProcessingException(SmppConstants.STATUS_INVPASWD);
        }

        if (!cred.isIpAllowed(ipAddress)) {
            logger.warn("SMPP Bind failed: IP [{}] is not whitelisted for systemId '{}'", ipAddress, systemId);
            throw new SmppProcessingException(SmppConstants.STATUS_BINDFAIL);
        }

        logger.info("Successfully authenticated SMPP Bind for systemId '{}' from IP [{}]", systemId, ipAddress);

        return new BasicSmppSessionContext(worker, cred);
    }
}
