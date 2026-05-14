package gr.cytech.sendium.core.smpp.server;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.SmppProcessingException;
import gr.cytech.sendium.core.message.StandardMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmppServerSessionHandlerTest {

    @Mock private SmppServerWorker<StandardMessage> worker;
    @Mock private SmppSession session;
    @Mock private SmppSessionContext sessionContext;
    @Mock private SubmitSmProcessor<StandardMessage> submitProcessor;
    @Mock private SmppSessionConfiguration sessionConfiguration;

    private SmppServerSessionHandler<StandardMessage> handler;

    @BeforeEach
    void setUp() {
        // Mock the max rate for the RateLimiter initialization in the constructor
        when(worker.getMaxRate(any())).thenReturn(100.0);

        // Setup session configuration to return a valid account ID to pass getAccountId() check
        when(session.getConfiguration()).thenReturn(sessionConfiguration);
        when(sessionConfiguration.getName()).thenReturn("test-account");

        // Initialize the handler
        handler = new SmppServerSessionHandler<>(worker, 12345L, session, sessionContext, submitProcessor);
    }

    @Test
    void handleSubmitSm_whenSubmitProcessorThrowsSmppProcessingException_shouldEnqueueNack() throws Exception {
        // Arrange: Create a strictly valid SubmitSm PDU so it passes initial validations
        SubmitSm submitSm = new SubmitSm();
        submitSm.setShortMessage("Hello SMPP".getBytes(StandardCharsets.UTF_8));
        submitSm.setDataCoding(SmppConstants.DATA_CODING_DEFAULT);

        // Mock the worker charset to bypass the SmppServerUtil.getMessageBody requirement
        when(worker.getCharsetGsm()).thenReturn(StandardCharsets.UTF_8.toString());

        // Define the specific error code we expect to be handled
        int expectedErrorCode = SmppConstants.STATUS_INVCMDID;

        // Mock the processor to throw the SmppProcessingException when called
        when(submitProcessor.processSubmitSm(
                eq(submitSm),
                eq(sessionContext),
                any(), // ValidatedMessageBody
                any(Timestamp.class)
        )).thenThrow(new SmppProcessingException(expectedErrorCode));

        // Act: Fire the method
        handler.handleSubmitSm(submitSm);

        // Assert: Capture the PduResponse enqueued to the out queue and assert its status
        ArgumentCaptor<SubmitSmResp> respCaptor = ArgumentCaptor.forClass(SubmitSmResp.class);
        verify(worker).enqueueOut(respCaptor.capture());

        SubmitSmResp capturedResp = respCaptor.getValue();
        assertThat(capturedResp.getCommandStatus()).isEqualTo(expectedErrorCode);
    }
}