package gr.cytech.sendium.core.storage.pending;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gr.cytech.sendium.core.message.DlrReturnMetadata;
import gr.cytech.sendium.core.message.StandardMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Detached accepted-message data with an explicit field mapping, not reflective message serialization. */
public final class PendingMessageData {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private final ObjectNode fields;

    private PendingMessageData(ObjectNode fields) {
        this.fields = fields;
    }

    public static PendingMessageData capture(StandardMessage message) {
        if (message == null || message.getClass() != StandardMessage.class || !Double.isFinite(message.marginPercentage)) {
            throw PendingRecordFields.unsupported();
        }
        ObjectNode data = NODES.objectNode();
        data.put("from", message.from);
        data.put("to", message.to);
        data.put("timestamp", message.timestamp);
        data.put("type", message.type);
        data.put("body", message.body);
        data.put("serial", message.serial);
        data.put("messageCenter", message.message_center);
        data.put("ownerId", message.owner_id);
        data.put("systemId", message.systemId);
        data.put("priority", message.priority);
        data.put("acked", message.acked);
        data.put("msgId", message.msgId);
        data.put("expr", message.expr);
        data.put("rtxCnt", message.rtxCnt);
        data.put("binbody", message.binbody);
        data.put("binheader", message.binheader);
        data.put("flash", message.flash);
        data.put("mclass", message.mclass);
        data.put("ttl", message.ttl);
        data.put("ddt", message.ddt);
        data.put("dcs", message.dcs);
        data.put("onetwork", message.onetwork);
        data.put("cnetwork", message.cnetwork);
        data.put("ingateway", message.ingateway);
        data.put("outgateway", message.outgateway);
        data.put("state", message.state);
        data.put("cost", message.cost);
        data.put("ctstamp", message.ctstamp);
        data.put("sutstamp", message.sutstamp);
        data.put("errcode", message.errcode);
        data.put("extrid", message.extrid);
        data.put("nextTarget", message.nextTarget);
        data.set("field1", PendingExtensionValues.encode(message.field1));
        data.set("field2", PendingExtensionValues.encode(message.field2));
        data.set("field3", PendingExtensionValues.encode(message.field3));
        data.set("field4", PendingExtensionValues.encode(message.field4));
        data.set("field5", PendingExtensionValues.encode(message.field5));
        data.set("field6", PendingExtensionValues.encode(message.field6));
        data.set("field7", PendingExtensionValues.encode(message.field7));
        data.set("field8", PendingExtensionValues.encode(message.field8));
        data.set("field9", PendingExtensionValues.encode(message.field9));
        data.set("field10", PendingExtensionValues.encode(message.field10));
        data.put("paid", message.paid);
        data.put("metadata", message.metadata);
        data.put("smsCnt", message.smsCnt);
        data.put("smsSubmitCnt", message.smsSubmitCnt);
        data.set("attrs", PendingExtensionValues.encode(message.attrs));
        data.set("reassembledParts", strings(message.reassembledParts));
        data.set("dlrReturnMetadata", dlr(message.dlrReturnMetadata));
        data.set("tlvs", tlvs(message.tlvs));
        data.put("dispatchType", message.dispatchType);
        data.put("hlrRoute", message.hlrRoute);
        data.put("marginPercentage", message.marginPercentage);
        data.put("marginStatus", message.marginStatus == null ? null : message.marginStatus.name());
        return new PendingMessageData(data);
    }

    public StandardMessage restore() {
        StandardMessage message = new StandardMessage();
        message.from = text("from");
        message.to = text("to");
        message.timestamp = text("timestamp");
        message.type = integer("type");
        message.body = text("body");
        message.serial = text("serial");
        message.message_center = text("messageCenter");
        message.owner_id = text("ownerId");
        message.systemId = text("systemId");
        message.priority = integer("priority");
        message.acked = bool("acked");
        message.msgId = integer("msgId");
        message.expr = longValue("expr");
        message.rtxCnt = integer("rtxCnt");
        message.binbody = text("binbody");
        message.binheader = text("binheader");
        message.flash = bool("flash");
        message.mclass = integer("mclass");
        message.ttl = integer("ttl");
        message.ddt = integer("ddt");
        message.dcs = (byte) PendingRecordFields.integer(field("dcs"), Byte.MIN_VALUE, Byte.MAX_VALUE);
        message.onetwork = integer("onetwork");
        message.cnetwork = integer("cnetwork");
        message.ingateway = text("ingateway");
        message.outgateway = text("outgateway");
        message.state = integer("state");
        message.cost = longValue("cost");
        message.ctstamp = longValue("ctstamp");
        message.sutstamp = longValue("sutstamp");
        message.errcode = text("errcode");
        message.extrid = text("extrid");
        message.nextTarget = text("nextTarget");
        message.field1 = PendingExtensionValues.decode(field("field1"));
        message.field2 = PendingExtensionValues.decode(field("field2"));
        message.field3 = PendingExtensionValues.decode(field("field3"));
        message.field4 = PendingExtensionValues.decode(field("field4"));
        message.field5 = PendingExtensionValues.decode(field("field5"));
        message.field6 = PendingExtensionValues.decode(field("field6"));
        message.field7 = PendingExtensionValues.decode(field("field7"));
        message.field8 = PendingExtensionValues.decode(field("field8"));
        message.field9 = PendingExtensionValues.decode(field("field9"));
        message.field10 = PendingExtensionValues.decode(field("field10"));
        message.paid = bool("paid");
        message.metadata = text("metadata");
        message.smsCnt = integer("smsCnt");
        message.smsSubmitCnt = integer("smsSubmitCnt");
        message.attrs = attributes(field("attrs"));
        message.reassembledParts = readStrings(field("reassembledParts"));
        message.dlrReturnMetadata = readDlr(field("dlrReturnMetadata"));
        message.tlvs = readTlvs(field("tlvs"));
        message.dispatchType = integer("dispatchType");
        message.hlrRoute = text("hlrRoute");
        message.marginPercentage = PendingRecordFields.decimal(field("marginPercentage"));
        String status = text("marginStatus");
        try {
            message.marginStatus = status == null ? null : StandardMessage.MarginStatus.valueOf(status);
        } catch (IllegalArgumentException failure) {
            throw PendingRecordFields.corrupt();
        }
        return message;
    }

    String gatewayId() {
        return text("serial");
    }

    ObjectNode toNode() {
        // Restore/capture also copies binary nodes, whose backing arrays deepCopy alone would share.
        return capture(restore()).fields;
    }

    static PendingMessageData fromNode(JsonNode node) {
        if (!node.isObject()) {
            throw PendingRecordFields.corrupt();
        }
        return capture(new PendingMessageData((ObjectNode) node).restore());
    }

    private JsonNode field(String name) {
        return PendingRecordFields.required(fields, name);
    }

    private String text(String name) {
        return PendingRecordFields.text(field(name));
    }

    private int integer(String name) {
        return PendingRecordFields.integer(field(name), Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private long longValue(String name) {
        return PendingRecordFields.integer(field(name));
    }

    private boolean bool(String name) {
        return PendingRecordFields.bool(field(name));
    }

    private static JsonNode strings(ArrayList<String> values) {
        if (values == null) {
            return NODES.nullNode();
        }
        var result = NODES.arrayNode();
        values.forEach(result::add);
        return result;
    }

    private static ArrayList<String> readStrings(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw PendingRecordFields.corrupt();
        }
        var result = new ArrayList<String>();
        node.forEach(value -> result.add(PendingRecordFields.text(value)));
        return result;
    }

    private static JsonNode tlvs(HashMap<String, String> values) {
        if (values == null) {
            return NODES.nullNode();
        }
        var result = NODES.objectNode();
        try {
            new TreeMap<>(values).forEach(result::put);
        } catch (ClassCastException | NullPointerException failure) {
            throw PendingRecordFields.unsupported();
        }
        return result;
    }

    private static HashMap<String, String> readTlvs(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw PendingRecordFields.corrupt();
        }
        var result = new HashMap<String, String>();
        node.properties().forEach(entry -> result.put(entry.getKey(), PendingRecordFields.text(entry.getValue())));
        return result;
    }

    private static Map<String, Object> attributes(JsonNode node) {
        Object value = PendingExtensionValues.decode(node);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw PendingRecordFields.corrupt();
        }
        var result = new HashMap<String, Object>();
        map.forEach((key, item) -> result.put((String) key, item));
        return result;
    }

    private static JsonNode dlr(DlrReturnMetadata metadata) {
        if (metadata == null) {
            return NODES.nullNode();
        }
        if (metadata.channel() == null) {
            throw PendingRecordFields.unsupported();
        }
        var result = NODES.objectNode();
        result.put("channel", metadata.channel().name());
        result.put("accountId", metadata.accountId());
        result.put("systemId", metadata.systemId());
        result.put("sourceAddress", metadata.sourceAddress());
        result.put("destinationAddress", metadata.destinationAddress());
        result.put("forwardDlrUrl", metadata.forwardDlrUrl());
        return result;
    }

    private static DlrReturnMetadata readDlr(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw PendingRecordFields.corrupt();
        }
        try {
            var channel = DlrReturnMetadata.DeliveryChannel.valueOf(
                    PendingRecordFields.nonNullText(PendingRecordFields.required(node, "channel")));
            return new DlrReturnMetadata(channel,
                    PendingRecordFields.text(PendingRecordFields.required(node, "accountId")),
                    PendingRecordFields.text(PendingRecordFields.required(node, "systemId")),
                    PendingRecordFields.text(PendingRecordFields.required(node, "sourceAddress")),
                    PendingRecordFields.text(PendingRecordFields.required(node, "destinationAddress")),
                    PendingRecordFields.text(PendingRecordFields.required(node, "forwardDlrUrl")));
        } catch (IllegalArgumentException failure) {
            throw PendingRecordFields.corrupt();
        }
    }
}
