package gr.cytech.sendium.util;

import com.cloudhopper.smpp.pdu.Pdu;
import gr.cytech.sendium.conf.SendiumConfigurationProvider;
import gr.cytech.sendium.core.message.StandardMessage;

import java.util.Collection;
import java.util.Map;

public final class MessageTrace {
    public static final String EVENT_ACCEPTED = "message.accepted";
    public static final String EVENT_DELIVER_SENT = "message.deliver.sent";
    public static final String EVENT_DELIVER_ENQUEUED = "message.deliver.enqueued";
    public static final String EVENT_DELIVER_FAILED = "message.deliver.failed";
    public static final String EVENT_DLR = "message.dlr";
    public static final String EVENT_DROPPED = "message.dropped";
    public static final String EVENT_DELIVERY_FAILED = "message.delivery.failed";
    public static final String EVENT_DELIVERY_RETRY = "message.delivery.retry";
    public static final String EVENT_ENQUEUED = "message.enqueued";
    public static final String EVENT_OPERATOR_LINKED = "message.operator.linked";
    public static final String EVENT_ROUTED = "message.routed";
    public static final String EVENT_ROUTING_MISS = "message.routing.miss";
    public static final String EVENT_SUBMITTED = "message.submitted";
    public static final String EVENT_SUBMIT_RESPONSE = "message.submit.response";
    public static final String[] TRACE_MODE = {"message.trace.mode", TraceMode.NECESSARY.value};

    private static final String MISSING = "-";

    private MessageTrace() {
    }

    public static String identifiers(StandardMessage msg) {
        if (msg == null) {
            return "serial=- msgId=- extrid=-";
        }

        String serial = value(msg.serial);
        return "serial=" + serial +
                " msgId=" + msg.msgId +
                " extrid=" + value(msg.extrid) +
                " type=" + value(StandardMessage.getType(msg.type)) +
                " typeId=" + msg.type +
                " priority=" + msg.priority +
                " acked=" + msg.acked +
                " dcs=" + msg.dcs +
                " mclass=" + msg.mclass +
                " ttl=" + msg.ttl +
                " ddt=" + msg.ddt +
                " state=" + msg.state +
                " errcode=" + value(msg.errcode) +
                " rtxCnt=" + msg.rtxCnt +
                " accountId=" + value(msg.owner_id) +
                " systemId=" + value(msg.systemId) +
                " messageCenter=" + value(msg.message_center) +
                " ingateway=" + value(msg.ingateway) +
                " outgateway=" + value(msg.outgateway) +
                " nextTarget=" + value(msg.nextTarget) +
                " onetwork=" + msg.onetwork +
                " cnetwork=" + msg.cnetwork +
                " smsCnt=" + msg.smsCnt +
                " submitParts=" + msg.smsSubmitCnt +
                " reassembledParts=" + size(msg.reassembledParts) +
                " hasUdh=" + hasValue(msg.binheader) +
                " tlvCount=" + size(msg.tlvs);
    }

    public static String pdu(Pdu pdu) {
        if (pdu == null) {
            return "pduCommandId=- pduSequence=- pduStatus=-";
        }

        return "pduCommandId=" + pdu.getCommandId() +
                " pduSequence=" + pdu.getSequenceNumber() +
                " pduStatus=" + pdu.getCommandStatus();
    }

    public static String value(String value) {
        if (value == null || value.isBlank()) {
            return MISSING;
        }

        return value.replace('\r', '_')
                .replace('\n', '_')
                .replace('\t', '_')
                .replace(' ', '_');
    }

    public static boolean shouldLog(SendiumConfigurationProvider configurationProvider, String eventName) {
        String mode = configurationProvider == null ? TRACE_MODE[1] : configurationProvider.getPrpt(TRACE_MODE);
        return shouldLog(mode, eventName);
    }

    public static boolean shouldLog(String mode, String eventName) {
        return TraceMode.from(mode).allows(eventName);
    }

    private static boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    private static int size(Collection<?> values) {
        return values == null ? 0 : values.size();
    }

    private static int size(Map<?, ?> values) {
        return values == null ? 0 : values.size();
    }

    private enum TraceMode {
        OFF("off"),
        NECESSARY("necessary"),
        ALL("all");

        private final String value;

        TraceMode(String value) {
            this.value = value;
        }

        private static TraceMode from(String value) {
            String normalized = value == null ? "" : value.trim();
            if (OFF.value.equalsIgnoreCase(normalized)) {
                return OFF;
            } else if (ALL.value.equalsIgnoreCase(normalized)) {
                return ALL;
            } else {
                return NECESSARY;
            }
        }

        private boolean allows(String eventName) {
            return switch (this) {
                case OFF -> false;
                case NECESSARY -> isNecessaryEvent(eventName);
                case ALL -> true;
            };
        }

        private boolean isNecessaryEvent(String eventName) {
            return switch (eventName) {
                case EVENT_ACCEPTED, EVENT_SUBMITTED, EVENT_DLR, EVENT_DELIVER_SENT -> true;
                default -> false;
            };
        }
    }
}
