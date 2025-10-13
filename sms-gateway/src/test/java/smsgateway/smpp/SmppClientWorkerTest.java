package smsgateway.smpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.SmppSessionHandler;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import smsgateway.dto.IncomingSms;

public class SmppClientWorkerTest {

    private SmppClientWorker worker;

    @Mock private VendorConf mockVendor;
    @Mock private SmppClientHolder mockClientHolder;
    @Mock private DefaultSmppClient mockSmppClient;
    @Mock private SmppSession mockSmppSession;
    @Mock private ScheduledExecutorService mockEnquireLinkExecutorService;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        when(mockVendor.getId()).thenReturn("test-vendor");
        // Default TPS for most tests, can be overridden in specific tests
        when(mockVendor.getTransactionsPerSecond()).thenReturn(0.0); // No delay by default
        when(mockVendor.getReconnectIntervalSeconds()).thenReturn(1); // Short reconnect for tests
        when(mockClientHolder.resource()).thenReturn(mockSmppClient);
        when(mockClientHolder.getEnquireLinkExecutor()).thenReturn(mockEnquireLinkExecutorService);

        // Simulate successful binding
        when(mockSmppClient.bind(
                        Mockito.any(SmppSessionConfiguration.class),
                        Mockito.any(SmppSessionHandler.class)))
                .thenReturn(mockSmppSession);
        when(mockSmppSession.isBound()).thenReturn(true);

        worker = new SmppClientWorker(mockVendor, mockClientHolder, null, null);
    }

    @Test
    void testProcessMethod_EnqueuesMessage() {
        IncomingSms message = new IncomingSms();
        message.setFrom("sender1");
        message.setTo("recipient1");
        message.setText("Hello World");
        // No ID field in IncomingSms, and timestamp is optional for this test's logic
        worker.process(message, "testRule", "testDest");

        assertEquals(1, worker.getMessageQueue().size(), "Queue should contain one message.");
        assertEquals(
                message,
                worker.getMessageQueue().peek(),
                "The message in the queue should be the one processed.");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS) // Timeout to prevent test hanging
    void testRunMethod_DequeuesAndProcessesMessage() throws InterruptedException {
        IncomingSms message = new IncomingSms();
        message.setFrom("sender2");
        message.setTo("recipient2");
        message.setText("Test Run Method");
        message.setCoding(CharsetUtil.NAME_GSM); // Explicitly set coding
        // Use a CountDownLatch to signal when the message has been processed
        // Since direct log capture is harder with SLF4J without adding a test appender,
        // we'll rely on the fact that processing means the message is taken from the queue.
        CountDownLatch processingLatch = new CountDownLatch(1);

        // Add message to the queue
        assertTrue(worker.getMessageQueue().offer(message), "Message should be added to the queue");
        assertEquals(
                1,
                worker.getMessageQueue().size(),
                "Queue should have 1 message before worker starts.");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(
                () -> {
                    worker.run();
                    // This part might not be reached if worker.run() loops indefinitely without
                    // interruption.
                    // However, for a single message test, if it processes and the test is about
                    // that one message,
                    // it might complete its relevant work or get interrupted by shutdownNow.
                });

        // Instead of waiting for a log, we will wait for the queue to become empty
        // as an indication that the message was taken for processing.
        // This is an indirect way to check if processing started.
        boolean messageTaken = false;
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < 3000) { // Wait up to 3 seconds
            if (worker.getMessageQueue().isEmpty()) {
                messageTaken = true;
                processingLatch.countDown(); // Signal that message was taken
                break;
            }
            Thread.sleep(50); // Poll every 50ms
        }

        assertTrue(messageTaken, "Worker should have taken the message from the queue.");
        assertTrue(
                processingLatch.await(1, TimeUnit.MILLISECONDS),
                "Processing latch should have counted down."); // Should be immediate if
        // messageTaken is true

        // After processing, the queue should be empty. This is already asserted by `messageTaken`.
        assertTrue(
                worker.getMessageQueue().isEmpty(),
                "Queue should be empty after message is processed.");

        executor.shutdownNow(); // Interrupts the worker thread
        assertTrue(
                executor.awaitTermination(1, TimeUnit.SECONDS),
                "Executor service should terminate.");
        // No handler to remove for SLF4J in this basic setup
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS) // Timeout to prevent test hanging
    void testRunMethod_HandlesInterruption() throws InterruptedException {
        Thread workerThread = new Thread(worker);
        workerThread.start();

        // Give the thread a moment to start and block on messageQueue.take()
        // This is not ideal, but pragmatic for this specific scenario.
        // A more robust solution might involve a custom BlockingQueue that signals when take() is
        // called.
        Thread.sleep(100); // Allow worker to enter take()

        assertTrue(workerThread.isAlive(), "Worker thread should be alive before interruption.");

        workerThread.interrupt();
        workerThread.join(1000); // Wait for the thread to terminate (max 1 second)

        assertFalse(
                workerThread.isAlive(), "Worker thread should be terminated after interruption.");
    }
}
