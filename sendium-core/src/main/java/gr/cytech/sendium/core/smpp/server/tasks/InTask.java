package gr.cytech.sendium.core.smpp.server.tasks;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.smpp.server.InEvent;
import gr.cytech.sendium.core.smpp.server.SmppServerWorker;
import gr.cytech.sendium.core.smpp.util.SmppServerUtil;
import gr.cytech.sendium.util.TimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class InTask<M extends StandardMessage> implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(InTask.class);
    private final SmppServerWorker<M> worker;
    private final LinkedBlockingQueue<InEvent<M>> inEventsQueue;
    private boolean keepOnRunning;
    private boolean pause;
    private List<InEvent<M>> storeInDB;
    private List<InEvent<M>> notifyFailure;

    public InTask(SmppServerWorker<M> worker) {
        this.worker = worker;
        this.inEventsQueue = worker.getInEventQueue();

        this.storeInDB = new ArrayList<>();
        this.notifyFailure = new ArrayList<>();

        keepOnRunning = true;
        pause = false;
    }

    @Override
    public void run() {
        while (keepOnRunning) {
            try {
                if (pause) {
                    logger.warn("PAUSING_PROCESSING");

                    do {
                        TimeUtils.sleep(100, TimeUnit.MILLISECONDS);
                    } while (pause && keepOnRunning);

                    if (!pause) {
                        logger.warn("RESUMING_PROCESSING");
                    }
                    continue;
                }
                processEvents();
            } catch (Exception ex) {
                logger.error("Exception caught in worker", ex);
            }
        }
    }

    public void die() {
        keepOnRunning = false;
    }

    public void processEvents() {
        int currentBatchSize = 0;
        var messageStore = worker.getMessageStore();
        if (messageStore == null) {
            return;
        }
        int maxBatchSize = messageStore.getInsertBatchSize();
        long maxWaitPeriod = messageStore.getInsertBatchPeriod();
        long start = System.currentTimeMillis();
        long remainingTime;

        //as long as the batch has not completed and the maximum waiting time has not passed
        //process if any events them as soon as possible
        do {
            remainingTime = start + maxWaitPeriod - System.currentTimeMillis();
            InEvent ine = null;
            try {
                //gather and populate the in event with all needed information
                ine = inEventsQueue.poll(remainingTime, TimeUnit.MILLISECONDS);
                if (ine != null) {
                    processInEvent(ine);
                    currentBatchSize++;
                }
            } catch (InterruptedException e) {
                logger.warn("Exception caught while waiting for in event {}", ine, e);
            }
        } while (currentBatchSize < maxBatchSize && remainingTime >= 0);

        //if there are messages to be stored in db, dispatch the hard work to the executor
        if (!storeInDB.isEmpty()) {
            logger.debug("InTask Batch: {}", storeInDB.size());
            worker.persistMessagesIn(storeInDB);
            storeInDB = new ArrayList<>(maxBatchSize + 1);
        }

        if (!notifyFailure.isEmpty()) {
            notifyClientsForFailure(notifyFailure);
            notifyFailure = new ArrayList<>();
        }
    }

    public void processInEvent(InEvent<M> ine) {
        try {
            logger.trace("preparing storage for submit_sm: {}", ine.submitSm);
            storeInDB.add(ine);
        } catch (Exception ex) {
            logger.error("Exception caught during the batch db insert", ex);
            notifyFailure.add(ine);
        }
    }

    private void notifyClientsForFailure(Collection<InEvent<M>> data) {
        for (InEvent<M> out : data) {
            SubmitSmResp rsp = SmppServerUtil.createSubmitRsp(out.submitSm, SmppConstants.STATUS_SUBMITFAIL, null);
            worker.enqueueOut(rsp);
        }
    }

    public List<InEvent<M>> getStoreInDB() {
        return storeInDB;
    }

    public List<InEvent<M>> getNotifyFailure() {
        return notifyFailure;
    }
}
