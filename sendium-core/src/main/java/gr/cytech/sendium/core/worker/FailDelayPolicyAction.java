package gr.cytech.sendium.core.worker;

/**
 * Created by peponakis on 4/5/16.
 */
public class FailDelayPolicyAction {

    /**
     * The amount of milliseconds to sleep before performing the {@link #action}.
     */
    public final long sleepBeforeActionMs;
    /**
     * The amount of milliseconds to sleep after performing the {@link #action}.
     */
    public final long sleepAfterActionMs;
    /**
     * The {@link Action} to perform
     */
    public final Action action;

    public FailDelayPolicyAction(Action action, long sleepBeforeActionMs, long sleepAfterActionMs) {
        this.sleepBeforeActionMs = sleepBeforeActionMs;
        this.sleepAfterActionMs = sleepAfterActionMs;
        this.action = action;
    }

    public enum Action {
        /**
         * Don't do anything, just sleep as long as {@link #sleepBeforeActionMs} and {@link #sleepAfterActionMs} define.
         * This will be handled as {@link #RE_ENQUEUE_ROUTER} in {@link FailDelayPolicy.Stage#WORKER_END_RETRY}
         */
        SLEEP,
        /**
         * Re-enqueue message to worker.
         * This will be handled as {@link #RE_ENQUEUE_ROUTER} in {@link FailDelayPolicy.Stage#WORKER_END_RETRY}
         */
        RE_ENQUEUE_WORKER,
        /**
         * Re-enqueue to routing table.
         */
        RE_ENQUEUE_ROUTER,
        /**
         * Re-enqueue to worker after a specified delay.
         * This will be handled as {@link #RE_ENQUEUE_ROUTER} in {@link FailDelayPolicy.Stage#WORKER_END_RETRY}
         */
        RE_ENQUEUE_WORKER_DELAYED,
        /**
         * Do a custom action, defined by the worker.
         */
        CUSTOM
    }
}
