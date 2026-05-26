package gr.cytech.sendium.routing;

import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.external.filter.FilterException;
import gr.cytech.sendium.external.filter.FilterStatusCodes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractRoutingManagerTest {

    @Test
    void getNextMessageInQueueAndRoute_WhenPausedReEnqueuesWithoutRouting() {
        TestRoutingManager manager = new TestRoutingManager();
        StandardMessage msg = new StandardMessage();
        manager.pause = true;
        manager.incoming.add(msg);

        manager.getNextMessageInQueueAndRoute();

        assertEquals(0, manager.lookupCalls);
        assertEquals(List.of(msg), manager.enqueuedToRouter);
    }

    @Test
    void getNextMessageInQueueAndRoute_WhenNoRouteReEnqueuesMessage() {
        TestRoutingManager manager = new TestRoutingManager();
        StandardMessage msg = new StandardMessage();
        manager.incoming.add(msg);
        manager.lookupResult = new RoutingLookupResult(new ArrayList<>(), false);

        manager.getNextMessageInQueueAndRoute();

        assertEquals(1, manager.lookupCalls);
        assertEquals(List.of(msg), manager.enqueuedToRouter);
    }

    @Test
    void getNextMessageInQueueAndRoute_WhenFilterDropsMessageDoesNotReEnqueue() {
        TestRoutingManager manager = new TestRoutingManager();
        StandardMessage msg = new StandardMessage();
        manager.incoming.add(msg);
        manager.lookupThrowable = filterException(FilterStatusCodes.DROP, msg);

        manager.getNextMessageInQueueAndRoute();

        assertTrue(manager.enqueuedToRouter.isEmpty());
        assertEquals(0, msg.rtxCnt);
    }

    @Test
    void getNextMessageInQueueAndRoute_WhenFilterRequestsRetryIncrementsAndReEnqueues() {
        TestRoutingManager manager = new TestRoutingManager();
        StandardMessage msg = new StandardMessage();
        manager.incoming.add(msg);
        manager.lookupThrowable = filterException(FilterStatusCodes.RETRY, msg);

        manager.getNextMessageInQueueAndRoute();

        assertEquals(1, msg.rtxCnt);
        assertEquals(List.of(msg), manager.enqueuedToRouter);
    }

    @Test
    void getNextMessageInQueueAndRoute_WhenGenericExceptionExceedsRetryLimitMovesToUnexpectedFailure() {
        TestRoutingManager manager = new TestRoutingManager();
        StandardMessage msg = new StandardMessage();
        msg.rtxCnt = 50;
        manager.incoming.add(msg);
        manager.lookupThrowable = new IOException("routing failed");

        manager.getNextMessageInQueueAndRoute();

        assertEquals(51, msg.rtxCnt);
        assertEquals(List.of(msg), manager.unexpectedFailures);
        assertTrue(manager.enqueuedToRouter.isEmpty());
    }

    @Test
    void enqueueFailedToQueueRetriesAfterTransientEnqueueFailure() {
        TestRoutingManager manager = new TestRoutingManager();
        StandardMessage msg = new StandardMessage();
        manager.failedq.add(msg);
        manager.enqueueFailuresRemaining = 1;

        manager.enqueueFailedToQueue();

        assertEquals(2, manager.enqueueAttempts);
        assertEquals(List.of(msg), manager.enqueuedToRouter);
        assertTrue(manager.failedq.isEmpty());
    }

    private FilterException filterException(FilterStatusCodes statusCode, StandardMessage msg) {
        AbstractOutWorker<StandardMessage> filter = mock(AbstractOutWorker.class);
        when(filter.getFullName()).thenReturn("filter.test");
        return new FilterException(filter, statusCode, msg, "filter result");
    }

    private static class TestRoutingManager extends AbstractRoutingManager<StandardMessage> {
        private final ArrayDeque<StandardMessage> incoming = new ArrayDeque<>();
        private final List<StandardMessage> enqueuedToRouter = new ArrayList<>();
        private final List<StandardMessage> unexpectedFailures = new ArrayList<>();
        private RoutingLookupResult lookupResult = RoutingLookupResult.EMPTY_RESULT;
        private Throwable lookupThrowable;
        private int lookupCalls;
        private int enqueueFailuresRemaining;
        private int enqueueAttempts;

        private TestRoutingManager() {
            RoutingTable defaultTable = new RoutingTable(RoutingFileParser.DEFAULT_ROUTING_TABLE_NAME, RoutingTable.TargetFunction.NORMAL);
            targets = new RoutingTargets(java.util.Map.of(RoutingFileParser.DEFAULT_ROUTING_TABLE_NAME, defaultTable), java.util.Map.of());
        }

        @Override
        protected StandardMessage getNextMessageToRoute() {
            return incoming.poll();
        }

        @Override
        protected void enqueueToRouterQueue(StandardMessage msg) throws InterruptedException {
            enqueueAttempts++;
            if (enqueueFailuresRemaining > 0) {
                enqueueFailuresRemaining--;
                throw new InterruptedException("transient enqueue failure");
            }
            enqueuedToRouter.add(msg);
        }

        @Override
        protected RoutingLookupResult lookupRoutingForMessage(StandardMessage pMsg, RoutingTable table) throws IOException {
            lookupCalls++;
            if (lookupThrowable instanceof FilterException filterException) {
                throw filterException;
            }
            if (lookupThrowable instanceof IOException ioException) {
                throw ioException;
            }
            if (lookupThrowable instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            return lookupResult;
        }

        @Override
        protected void handleMessageUnexpectedFailure(StandardMessage msg, Throwable e) {
            unexpectedFailures.add(msg);
        }

        @Override
        protected boolean getConfigBoolean(String[] prop) {
            return false;
        }

        @Override
        protected int getConfigInt(String[] prop) {
            return 1;
        }
    }
}
