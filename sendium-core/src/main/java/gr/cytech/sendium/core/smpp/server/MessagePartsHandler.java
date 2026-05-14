package gr.cytech.sendium.core.smpp.server;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.util.MessageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * This class implements a handler that collects message parts for various concatenated
 * messages and notifies the registered listener when either all the parts of a message have been
 * received or when the configured {@link MessagePartsHandler#timeoutInMilliseconds} has passed.
 * The message parts must have a valid UDH information in the {@link StandardMessage#binheader} based on the
 * {@link MessagePartsHandler#isSupportedMessagePart(StandardMessage)}.
 */
public class MessagePartsHandler<M extends StandardMessage> {
    private static final Logger logger = LoggerFactory.getLogger(MessagePartsHandler.class);

    private final Map<String, SortedSet<M>> pendingMessageParts;
    private final Map<String, Future<Boolean>> scheduledTasks;
    private final MessagePartsEventsListener<M> listener;

    private long timeoutInMilliseconds;
    private ScheduledThreadPoolExecutor executor;

    public MessagePartsHandler(MessagePartsEventsListener<M> listener, long timeoutInMilliseconds, ScheduledThreadPoolExecutor executor) {
        this.timeoutInMilliseconds = timeoutInMilliseconds;
        this.pendingMessageParts = new ConcurrentHashMap<>();
        this.scheduledTasks = new ConcurrentHashMap<>();
        this.listener = listener;
        this.executor = executor;
    }

    public MessagePartsHandler(MessagePartsEventsListener<M> listener, long timeoutInMilliseconds) {
        this(listener, timeoutInMilliseconds, new ScheduledThreadPoolExecutor(
                1, //We just need a single thread with a meaningful name
                Thread.ofVirtual()
                        .name(listener.getName() + "-MessagePartsHandler-Executor-", 1)
                        .uncaughtExceptionHandler((thread, throwable) ->
                                logger.warn("uncaught exception in {}-MessagePartsHandler-Executor", listener.getName(), throwable))
                        .factory()));
    }

    public boolean stop() {
        logger.info("Stopping MessagePartsHandler: {} ...", listener.getName());

        //process any scheduled tasks and stop the executor
        executor.shutdown();

        //notify the listener about any remaining received message parts
        for (Map.Entry<String, SortedSet<M>> entry : pendingMessageParts.entrySet()) {
            DelayedMessagePartsTask task = new DelayedMessagePartsTask(entry.getKey());
            task.call();
        }

        logger.info("MessagePartsHandler stopped.");
        return true;
    }

    public void setTimeoutInMilliseconds(long timeoutInMilliseconds) {
        this.timeoutInMilliseconds = timeoutInMilliseconds;
    }

    /**
     * Adds the part in the list of the received parts of the provided message. The message reference
     * is extracted based on the UDH information in the binheader field. In case the current part
     * is the last pending of the specific message the {@link MessagePartsEventsListener#onMessagePartsHandlingEvent}
     * is called. This method is thread safe, so that if many threads are adding parts for the same message the listener
     * will get notified only once with the sorted list of all the received parts.
     *
     * @param part The part to be added. It must be a valid part based on the {@link MessagePartsHandler#isSupportedMessagePart}
     * @throws IllegalArgumentException If the {@link MessagePartsHandler#isSupportedMessagePart} return false
     */
    public void addMessagePart(M part) throws IllegalArgumentException {
        if (!isSupportedMessagePart(part)) {
            throw new IllegalArgumentException("The provided message part does not contain a supported UDH");
        }
        String msgRefNum = MessageUtil.getMessageReference(part);

        //Create an empty message parts container if such not exists
        SortedSet<M> receivedParts = pendingMessageParts.computeIfAbsent(msgRefNum,
                k -> new ConcurrentSkipListSet<>(new MessagePartsComparator()));

        logger.debug("adding message part for msgRefNum: {} with udh: {}", msgRefNum, part.binheader);
        receivedParts.add(part);

        scheduledTasks.computeIfAbsent(msgRefNum, this::scheduleDelayedMessagePartsTask);

        //check if we have received all the message parts
        if (receivedParts.size() == MessageUtil.getNumberOfTotalParts(part)) {
            //cancel the task and get the message parts
            List messageParts = cancelScheduledDelayedMessagePartsTask(msgRefNum);
            if (messageParts != null && !messageParts.isEmpty()) {
                //notify the registered listener
                listener.onMessagePartsHandlingEvent(MessagePartsEventType.COMPLETE, messageParts);
            }
        }
    }

    private Future<Boolean> scheduleDelayedMessagePartsTask(String msgRefNum) {
        //schedule a task to notify the listener when a timeout occurs
        Callable<Boolean> delayedCall = new DelayedMessagePartsTask(msgRefNum);
        Future<Boolean> futureCall = executor.schedule(delayedCall, timeoutInMilliseconds, TimeUnit.MILLISECONDS);
        return futureCall;
    }

    /**
     * Cancels the scheduled delayed task for the given msgRefNum
     *
     * @param msgRefNum The reference number, common among all parts of the message
     * @return A list of the received parts
     */
    private List<M> cancelScheduledDelayedMessagePartsTask(String msgRefNum) {
        Future<Boolean> futureCall = scheduledTasks.remove(msgRefNum);
        List<M> receivedParts = null;
        //Remove all the related information because all message parts have been received
        if (futureCall != null && futureCall.cancel(false)) {
            Set<M> parts = pendingMessageParts.remove(msgRefNum);
            if (parts != null) {
                receivedParts = new ArrayList<>(parts);
            }
        }
        return receivedParts;
    }

    /**
     * Checks if the provided message contains a valid UDH that represents a part of either
     * an 8-bit or a 16-bit concatenated message. For more information check the
     * {@link MessageUtil#is8BitMessagePart} and {@link MessageUtil#is16BitMessagePart}
     *
     * @param message The message to be checked
     * @return True if the provided message has a supported UDH format
     */
    public boolean isSupportedMessagePart(M message) {
        return MessageUtil.is8BitMessagePart(message) || MessageUtil.is16BitMessagePart(message);
    }

    public enum MessagePartsEventType { COMPLETE, DELAYED }

    /**
     * This class implements a Comparator that checks the order of the received
     * parts of a {@link StandardMessage} based on the reference information encapsulated
     * in the {@link StandardMessage#binheader}. The part must be a valid based on the
     * {@link MessagePartsHandler#isSupportedMessagePart}
     */
    private class MessagePartsComparator implements Comparator<M> {
        public int compare(M part1, M part2) {
            int numOfPart1 = MessageUtil.getNumberOfCurrentPart(part1);
            int numOfPart2 = MessageUtil.getNumberOfCurrentPart(part2);

            return Integer.compare(numOfPart1, numOfPart2);
        }
    }

    /**
     * This runnable is executed when the configured {@link MessagePartsHandler#timeoutInMilliseconds}
     * expires without having received all the parts of a message. All receive parts
     */
    public class DelayedMessagePartsTask implements Runnable, Callable<Boolean> {
        private final String msgRefNum;

        public DelayedMessagePartsTask(String msgRefNum) {
            this.msgRefNum = msgRefNum;
        }

        public void run() {
            handleReceivedParts();
        }

        public Boolean call() {
            return handleReceivedParts();
        }

        private boolean handleReceivedParts() {
            try {
                Set<M> parts = pendingMessageParts.remove(msgRefNum);
                if (parts != null && !parts.isEmpty()) {
                    //Just remove the reference to the task as it has already been executed.
                    scheduledTasks.remove(msgRefNum);
                    //notify the registered listener
                    listener.onMessagePartsHandlingEvent(MessagePartsEventType.DELAYED, new ArrayList<>(parts));
                }
            } catch (Exception ex) {
                logger.error("exception caught while handling the delayed parts with msgRefNum: " + msgRefNum);
                return false;
            }
            return true;
        }
    }
}
