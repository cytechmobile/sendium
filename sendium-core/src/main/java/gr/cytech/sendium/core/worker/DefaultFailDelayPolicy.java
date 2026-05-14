package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;

/**
 * Created by peponakis on 4/5/16.
 */
public class DefaultFailDelayPolicy extends FailDelayPolicy {
    private final FailDelayPolicyAction workerFailAction;
    private final FailDelayPolicyAction workerEndFailAction;

    public DefaultFailDelayPolicy(
            FailDelayPolicyAction.Action workerFailAction, FailDelayPolicyAction.Action workerEndFailAction, long sleepBeforeWorker, long sleepBeforeRouter) {
        this.workerFailAction = new FailDelayPolicyAction(workerFailAction, sleepBeforeWorker, 0);
        this.workerEndFailAction = new FailDelayPolicyAction(workerEndFailAction, sleepBeforeRouter, 0);
    }

    @Override
    public FailDelayPolicyAction getActionForMessage(StandardMessage msg, Stage stage, int trial) {
        switch (stage) {
            case WORKER_RETRY:
                return workerFailAction;
            case WORKER_END_RETRY:
                return workerEndFailAction;
            default:
                return workerFailAction;
        }
    }
}
