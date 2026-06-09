package gr.cytech.sendium.core.smpp.server;

import com.cloudhopper.smpp.SmppServerSession;
import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.pdu.BaseBindResp;
import com.cloudhopper.smpp.pdu.BindTransceiver;
import com.cloudhopper.smpp.type.SmppProcessingException;
import gr.cytech.sendium.core.message.StandardMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmppServerBindHandlerTest {

    private static final String ACCOUNT_ID = "account-a";

    @Mock private SmppServerWorker<StandardMessage> worker;
    @Mock private SmppAuthenticationProvider authProvider;
    @Mock private SubmitSmProcessor<StandardMessage> submitProcessor;
    @Mock private SmppServerMessageStore<StandardMessage> messageStore;
    @Mock private SmppServerSession session;
    @Mock private SmppSessionConfiguration sessionConfiguration;
    @Mock private BaseBindResp bindResponse;

    private SmppServerBindHandler<StandardMessage> bindHandler;

    @BeforeEach
    void setUp() {
        bindHandler = new SmppServerBindHandler<>(worker, authProvider, submitProcessor);
    }

    @Test
    void sessionBindRequested_DoesNotRetainPendingContextsWhenBindIsRejectedBeforeSessionCreated() throws Exception {
        when(worker.getMaxConnectionsPerIP()).thenReturn(0);
        addExistingConnectionForAccount(ACCOUNT_ID);

        SmppSessionContext context = mock(SmppSessionContext.class);
        when(context.getAccountId()).thenReturn(ACCOUNT_ID);
        when(context.getMaxConnections()).thenReturn(1);
        when(authProvider.authenticate(anyString(), anyString(), anyString())).thenReturn(context);

        int bindRequests = 100;
        for (int i = 0; i < bindRequests; i++) {
            Long sessionId = (long) i;
            SmppSessionConfiguration bindConfiguration = new SmppSessionConfiguration(
                    SmppBindType.TRANSCEIVER,
                    "smpp-user-" + i,
                    "password"
            );
            bindConfiguration.setHost("127.0.0.2");

            assertThatThrownBy(() -> bindHandler.sessionBindRequested(sessionId, bindConfiguration, new BindTransceiver()))
                    .isInstanceOf(SmppProcessingException.class);
        }

        assertThat(pendingSessionContexts()).isEmpty();
    }

    @Test
    void sessionCreated_ReplaysUnpushedDlrsAfterSessionIsBound() throws Exception {
        stubSessionCreatedDefaults();

        bindHandler.sessionCreated(1L, session, bindResponse);

        verify(session).serverReady(any(SmppServerSessionHandler.class));
        verify(messageStore, timeout(1_000)).onClientConnected("smpp-user");
    }

    private void stubSessionCreatedDefaults() {
        when(worker.getMaxConnectionsPerIP()).thenReturn(0);
        when(worker.getMessageStore()).thenReturn(messageStore);
        when(session.getConfiguration()).thenReturn(sessionConfiguration);
        when(sessionConfiguration.getName()).thenReturn(ACCOUNT_ID);
        when(sessionConfiguration.getHost()).thenReturn("127.0.0.1");
        when(sessionConfiguration.getSystemId()).thenReturn("smpp-user");
        when(session.isBound()).thenReturn(true);
    }

    private void addExistingConnectionForAccount(String accountId) {
        SmppServerSessionHandler<StandardMessage> existingHandler = mock(SmppServerSessionHandler.class);
        when(existingHandler.getSession()).thenReturn(session);
        when(existingHandler.isBackupConnection()).thenReturn(false);
        when(session.getConfiguration()).thenReturn(sessionConfiguration);
        when(session.getBindType()).thenReturn(SmppBindType.TRANSCEIVER);
        when(session.getLocalType()).thenReturn(SmppSession.Type.SERVER);
        when(sessionConfiguration.getHost()).thenReturn("127.0.0.1");
        when(sessionConfiguration.getSystemId()).thenReturn("existing-smpp-user");

        bindHandler.connections.addConnection(accountId, existingHandler);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, SmppSessionContext> pendingSessionContexts() throws NoSuchFieldException, IllegalAccessException {
        Field field = SmppServerBindHandler.class.getDeclaredField("pendingSessionContexts");
        field.setAccessible(true);
        return (Map<Long, SmppSessionContext>) field.get(bindHandler);
    }
}
