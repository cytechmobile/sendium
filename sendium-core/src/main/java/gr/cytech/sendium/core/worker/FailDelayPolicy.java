package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;

/**
 * Created by peponakis on 4/5/16.
 */
public abstract class FailDelayPolicy {
    public abstract FailDelayPolicyAction getActionForMessage(StandardMessage message, Stage stage, int trial);

    public enum Stage {
        /**
         * The message is being re-tried in the worker.
         */
        WORKER_RETRY,
        /**
         * The message will not be re-tried by the worker.
         */
        WORKER_END_RETRY
    }
}
