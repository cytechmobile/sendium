package utils;

import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.worker.WorkerType;
import jakarta.enterprise.context.Dependent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Dependent
@WorkerType(CaptorWorker.TYPE)
public class CaptorWorker extends AbstractOutWorker<StandardMessage> {
    public static final String TYPE = "captor";
    public BlockingQueue<StandardMessage> captures;
    public boolean filter;

    protected CaptorWorker() {
        this.captures = new LinkedBlockingQueue<>();
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public boolean isFilter() {
        return filter;
    }

    @Override
    public StandardMessage doMessage(int pThreadIndex, StandardMessage pMsg) {
        captures.add(pMsg);
        return null;
    }
}
