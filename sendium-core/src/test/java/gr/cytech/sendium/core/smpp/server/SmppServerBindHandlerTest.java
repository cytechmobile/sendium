package gr.cytech.sendium.core.smpp.server;

import com.cloudhopper.smpp.SmppServerSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.pdu.BaseBindResp;
import gr.cytech.sendium.core.message.StandardMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmppServerBindHandlerTest {

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
        when(worker.getMaxConnectionsPerIP()).thenReturn(0);
        when(worker.getMessageStore()).thenReturn(messageStore);
        when(session.getConfiguration()).thenReturn(sessionConfiguration);
        when(sessionConfiguration.getName()).thenReturn("account-a");
        when(sessionConfiguration.getHost()).thenReturn("127.0.0.1");
        when(sessionConfiguration.getSystemId()).thenReturn("smpp-user");
        when(session.isBound()).thenReturn(true);
    }

    @Test
    void sessionCreated_ReplaysUnpushedDlrsAfterSessionIsBound() throws Exception {
        bindHandler.sessionCreated(1L, session, bindResponse);

        verify(session).serverReady(any(SmppServerSessionHandler.class));
        verify(messageStore, timeout(1_000)).onClientConnected("smpp-user");
    }
}
