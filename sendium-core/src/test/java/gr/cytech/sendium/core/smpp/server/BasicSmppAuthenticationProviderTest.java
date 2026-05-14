package gr.cytech.sendium.core.smpp.server;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.type.SmppProcessingException;
import gr.cytech.sendium.auth.CredentialFileWatcher;
import gr.cytech.sendium.external.WorkerResourceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BasicSmppAuthenticationProviderTest {

    @Mock private SmppServerWorker worker;
    @Mock private WorkerResourceProvider workerResourceProvider;
    @Mock private CredentialFileWatcher credentialFileWatcher;

    private BasicSmppAuthenticationProvider authProvider;

    @BeforeEach
    void setUp() {
        when(worker.getWorkerResources()).thenReturn(workerResourceProvider);
        when(workerResourceProvider.getCredentialFileWatcher()).thenReturn(credentialFileWatcher);
        authProvider = new BasicSmppAuthenticationProvider(worker);
    }

    @Test
    void authenticate_Success() throws SmppProcessingException {
        CredentialFileWatcher.Credential validCred = new CredentialFileWatcher.Credential(
                CredentialFileWatcher.CredentialType.SMPP,
                "account-123",
                "ProductX",
                "systemId123",
                "correctPassword",
                null,
                Set.of("192.168.1.1")
        );
        when(credentialFileWatcher.getValidCredentials()).thenReturn(Map.of("systemId123", validCred));

        SmppSessionContext context = authProvider.authenticate("systemId123", "correctPassword", "192.168.1.1");

        assertNotNull(context);
        assertEquals("systemId123", context.getSystemId());
        assertEquals("account-123", context.getAccountId());
        assertEquals("ProductX", context.getProduct());
    }

    @Test
    void authenticate_NullSystemId_ThrowsSmppProcessingException() {
        SmppProcessingException ex = assertThrows(SmppProcessingException.class, () ->
                authProvider.authenticate(null, "password", "192.168.1.1"));
        assertEquals(SmppConstants.STATUS_BINDFAIL, ex.getErrorCode());
    }

    @Test
    void authenticate_NullPassword_ThrowsSmppProcessingException() {
        SmppProcessingException ex = assertThrows(SmppProcessingException.class, () ->
                authProvider.authenticate("systemId", null, "192.168.1.1"));
        assertEquals(SmppConstants.STATUS_BINDFAIL, ex.getErrorCode());
    }

    @Test
    void authenticate_InvalidSystemId_ThrowsSmppProcessingException() {
        when(credentialFileWatcher.getValidCredentials()).thenReturn(Map.of());

        SmppProcessingException ex = assertThrows(SmppProcessingException.class, () ->
                authProvider.authenticate("unknownSystemId", "password", "192.168.1.1"));
        assertEquals(SmppConstants.STATUS_INVSYSID, ex.getErrorCode());
    }

    @Test
    void authenticate_WrongPassword_ThrowsSmppProcessingException() {
        CredentialFileWatcher.Credential validCred = new CredentialFileWatcher.Credential(
                CredentialFileWatcher.CredentialType.SMPP,
                "account-123",
                null,
                "systemId123",
                "correctPassword",
                null,
                null
        );
        when(credentialFileWatcher.getValidCredentials()).thenReturn(Map.of("systemId123", validCred));

        SmppProcessingException ex = assertThrows(SmppProcessingException.class, () ->
                authProvider.authenticate("systemId123", "wrongPassword", "192.168.1.1"));
        assertEquals(SmppConstants.STATUS_INVPASWD, ex.getErrorCode());
    }

    @Test
    void authenticate_IPNotWhitelisted_ThrowsSmppProcessingException() {
        CredentialFileWatcher.Credential validCred = new CredentialFileWatcher.Credential(
                CredentialFileWatcher.CredentialType.SMPP,
                "account-123",
                null,
                "systemId123",
                "correctPassword",
                null,
                Set.of("10.0.0.1")
        );
        when(credentialFileWatcher.getValidCredentials()).thenReturn(Map.of("systemId123", validCred));

        SmppProcessingException ex = assertThrows(SmppProcessingException.class, () ->
                authProvider.authenticate("systemId123", "correctPassword", "192.168.1.1"));
        assertEquals(SmppConstants.STATUS_BINDFAIL, ex.getErrorCode());
    }

    @Test
    void authenticate_WrongCredentialType_ThrowsSmppProcessingException() {
        CredentialFileWatcher.Credential httpCred = new CredentialFileWatcher.Credential(
                CredentialFileWatcher.CredentialType.HTTP,
                "account-123",
                null,
                "httpSystemId",
                null,
                "apiKey123",
                null
        );
        when(credentialFileWatcher.getValidCredentials()).thenReturn(Map.of("apiKey123", httpCred));

        SmppProcessingException ex = assertThrows(SmppProcessingException.class, () ->
                authProvider.authenticate("httpSystemId", "anyPassword", "192.168.1.1"));
        assertEquals(SmppConstants.STATUS_INVSYSID, ex.getErrorCode());
    }

    @Test
    void authenticate_IPWhitelistNull_AllowsAllIPs() throws SmppProcessingException {
        CredentialFileWatcher.Credential validCred = new CredentialFileWatcher.Credential(
                CredentialFileWatcher.CredentialType.SMPP,
                "account-123",
                null,
                "systemId123",
                "correctPassword",
                null,
                null
        );
        when(credentialFileWatcher.getValidCredentials()).thenReturn(Map.of("systemId123", validCred));

        SmppSessionContext context = authProvider.authenticate("systemId123", "correctPassword", "any.ip.address.here");

        assertNotNull(context);
    }

    @Test
    void authenticate_IPWhitelistEmpty_AllowsAllIPs() throws SmppProcessingException {
        CredentialFileWatcher.Credential validCred = new CredentialFileWatcher.Credential(
                CredentialFileWatcher.CredentialType.SMPP,
                "account-123",
                null,
                "systemId123",
                "correctPassword",
                null,
                Set.of()
        );
        when(credentialFileWatcher.getValidCredentials()).thenReturn(Map.of("systemId123", validCred));

        SmppSessionContext context = authProvider.authenticate("systemId123", "correctPassword", "any.ip.address.here");

        assertNotNull(context);
    }
}
