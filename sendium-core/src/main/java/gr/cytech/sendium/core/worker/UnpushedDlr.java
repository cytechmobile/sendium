package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
public class UnpushedDlr implements Serializable {
    private static final long serialVersionUID = 1L;

    public String systemId;
    public String accountId;
    public String from;
    public String to;
    public String serial;
    public int msgId;
    public int state;
    public String errcode;
    public boolean acked;
    public int priority;
    public List<String> reassembledParts;

    public UnpushedDlr() {
    }

    public static UnpushedDlr fromMessage(StandardMessage msg) {
        UnpushedDlr dlr = new UnpushedDlr();
        dlr.systemId = msg.systemId;
        dlr.accountId = msg.owner_id;
        dlr.from = msg.from;
        dlr.to = msg.to;
        dlr.serial = msg.serial;
        dlr.msgId = msg.msgId;
        dlr.state = msg.state;
        dlr.errcode = msg.errcode;
        dlr.acked = msg.acked;
        dlr.priority = msg.priority;
        dlr.reassembledParts = msg.reassembledParts == null ? null : new ArrayList<>(msg.reassembledParts);
        return dlr;
    }

    public StandardMessage toMessage() {
        StandardMessage msg = new StandardMessage();
        msg.type = StandardMessage.MSG_DLR;
        msg.systemId = systemId;
        msg.owner_id = accountId;
        msg.from = from;
        msg.to = to;
        msg.serial = serial;
        msg.msgId = msgId;
        msg.state = state;
        msg.errcode = errcode;
        msg.acked = acked;
        msg.priority = priority;
        msg.reassembledParts = reassembledParts == null ? null : new ArrayList<>(reassembledParts);
        return msg;
    }
}
