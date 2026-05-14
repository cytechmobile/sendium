package gr.cytech.sendium.core.smpp.server;

import gr.cytech.sendium.auth.CredentialFileWatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BasicSmppSessionContextTest {

    @Mock private SmppServerWorker worker;

    @BeforeEach
    void setUp() {
        when(worker.getDefaultMaxConnectionPerUser()).thenReturn(100);
        when(worker.getMaxPending()).thenReturn(500);
        when(worker.getWindowMonitorInterval()).thenReturn(15000L);
        when(worker.getResponseTimeout()).thenReturn(30000L);
        when(worker.getWriteTimeout()).thenReturn(30000L);
    }

    @Test
    void constructor_WithFullCredential_SetsAllValues() {
        CredentialFileWatcher.Credential cred = new CredentialFileWatcher.Credential(
                CredentialFileWatcher.CredentialType.SMPP,
                "account-123",
                "ProductName",
                "systemId123",
                "password123",
                null,
                Set.of("192.168.1.1", "10.0.0.1")
        );
        when(worker.getMaxRate("account-123")).thenReturn(50.0);

        BasicSmppSessionContext context = new BasicSmppSessionContext(worker, cred);

        assertEquals("systemId123", context.getSystemId());
        assertEquals("account-123", context.getAccountId());
        assertEquals("ProductName", context.getProduct());
        assertEquals(100, context.getMaxConnections());
        assertEquals(50.0, context.getMaxRate());
        assertEquals(500, context.getWindowSize());
        assertEquals(15000L, context.getWindowMonitorInterval());
        assertEquals(30000L, context.getRequestExpiryTimeout());
        assertEquals(30000L, context.getWriteTimeout());
    }

    @Test
    void constructor_WithNullAccountId_FallsBackToSystemId() {
        CredentialFileWatcher.Credential cred = new CredentialFileWatcher.Credential(
                CredentialFileWatcher.CredentialType.SMPP,
                null,
                null,
                "systemIdOnly",
                "password123",
                null,
                null
        );
        when(worker.getMaxRate(null)).thenReturn(25.0);

        BasicSmppSessionContext context = new BasicSmppSessionContext(worker, cred);

        assertEquals("systemIdOnly", context.getSystemId());
        assertEquals("systemIdOnly", context.getAccountId());
    }

    @Test
    void constructor_WithEmptyAccountId_FallsBackToSystemId() {
        CredentialFileWatcher.Credential cred = new CredentialFileWatcher.Credential(
                CredentialFileWatcher.CredentialType.SMPP,
                "",
                null,
                "systemIdOnly",
                "password123",
                null,
                null
        );
        when(worker.getMaxRate("")).thenReturn(25.0);

        BasicSmppSessionContext context = new BasicSmppSessionContext(worker, cred);

        assertEquals("systemIdOnly", context.getSystemId());
        assertEquals("systemIdOnly", context.getAccountId());
    }

    @Test
    void constructor_WithNullProduct_SetsEmptyProduct() {
        CredentialFileWatcher.Credential cred = new CredentialFileWatcher.Credential(
                CredentialFileWatcher.CredentialType.SMPP,
                "account-123",
                null,
                "systemId123",
                "password123",
                null,
                null
        );
        when(worker.getMaxRate("account-123")).thenReturn(10.0);

        BasicSmppSessionContext context = new BasicSmppSessionContext(worker, cred);

        assertEquals("", context.getProduct());
    }

    @Test
    void constructor_VerifyWorkerDefaultsAreUsed() {
        CredentialFileWatcher.Credential cred = new CredentialFileWatcher.Credential(
                CredentialFileWatcher.CredentialType.SMPP,
                "testAccount",
                null,
                "sysId",
                "pass",
                null,
                null
        );
        when(worker.getMaxRate("testAccount")).thenReturn(99.9);

        BasicSmppSessionContext context = new BasicSmppSessionContext(worker, cred);

        verify(worker).getDefaultMaxConnectionPerUser();
        verify(worker).getMaxRate("testAccount");
        verify(worker).getMaxPending();
        verify(worker).getWindowMonitorInterval();
        verify(worker).getResponseTimeout();
        verify(worker).getWriteTimeout();

        assertEquals(99.9, context.getMaxRate());
    }
}
