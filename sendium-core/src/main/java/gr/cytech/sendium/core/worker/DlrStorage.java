package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;

import java.util.List;

/**
 * Persistence boundary for delivery-receipt correlation and downstream SMPP replay state.
 */
public interface DlrStorage extends DlrMessageStorage {
    boolean saveUnpushedDlr(StandardMessage message);

    List<StandardMessage> getUnpushedDlrs(String systemId);

    /**
     * Claims replayable receipts within this storage instance. V1 targets one Sendium process and does not promise
     * distributed claim coordination across multiple gateway replicas.
     */
    List<StandardMessage> claimUnpushedDlrs(String systemId);

    boolean removeUnpushedDlr(StandardMessage message);

    void releaseUnpushedDlrClaim(StandardMessage message);

}
