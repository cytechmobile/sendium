// CHECKSTYLE:OFF
package gr.cytech.sendium.core.message;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class StandardMessage implements Comparable<StandardMessage>, CoreMessage, Serializable, Cloneable {
    private static final Logger logger = LoggerFactory.getLogger(StandardMessage.class);

    public static final int HIGH_PRIORITY = 3;
    public static final int NORMAL_PRIORITY = 2;
    public static final int LOW_PRIORITY = 1;

    public static final int MSG_TEXT = 0;
    public static final int MSG_USER_TYPE1 = 1;
    public static final int MSG_USER_TYPE2 = 2;
    public static final int MSG_USER_TYPE3 = 3;
    public static final int MSG_USER_TYPE4 = 4;
    public static final int MSG_USER_TYPE5 = 5;
    public static final int MSG_USER_TYPE6 = 6;
    public static final int MSG_USER_TYPE7 = 7;
    public static final int MSG_USER_TYPE8 = 8;
    public static final int MSG_USER_TYPE9 = 9;
    public static final int MSG_BINARY = 10;
    public static final int MSG_UCS2 = 11;
    public static final int MSG_VCARD = 12;
    public static final int MSG_VCAL = 13;
    public static final int MSG_FLASH = 14;
    public static final int MSG_PING = 15;
    public static final int MSG_BOOKMARK = 16;
    public static final int MSG_PUSH = 17;
    public static final int MSG_DLR = 18;
    public static final int MSG_HLR = 19;
    public static final int MSG_HLR_RSP = 20;
    public static final int MSG_MMS = 21;
    public static final int MSG_VIBER = 22;
    public static final int MSG_DLR_VIBER_UNUSED = 23;
    public static final int MSG_DCB = 24;
    public static final String MSG_TYPE_TEXT = "t";
    public static final String MSG_TYPE_UCS2 = "u";
    public static final String MSG_TYPE_BINARY = "b";
    public static final String MSG_TYPE_PUSH = "w";
    public static final String MSG_TYPE_FLASH = "f";
    public static final String MSG_TYPE_VCARD = "V";
    public static final String MSG_TYPE_VCAL = "C";
    public static final String MSG_TYPE_PING = "s";
    public static final String MSG_TYPE_BOOKMARK = "k";
    public static final String MSG_TYPE_DLR = "l";
    public static final String MSG_TYPE_HLR = "h";
    public static final String MSG_TYPE_HLR_RSP = "r";
    public static final String MSG_TYPE_MMS = "m";
    public static final String MSG_TYPE_CUSTOM = "o";
    public static final String MSG_TYPE_VIBER = "v";
    public static final String MSG_TYPE_DCB = "d";
    /**
     * Current network status code: Null value
     */
    public static final int MSG_NETWORK_NULL = 0;
    /**
     * Current network status code: Unexpected data value
     */
    public static final int MSG_NETWORK_UNEXPECTED = -1;
    /**
     * Current network status code: Subscriber does not exists
     */
    public static final int MSG_NETWORK_NOTEXISTS = -2;
    /**
     * Current network status code: Data missing
     */
    public static final int MSG_NETWORK_NOTZONE = -3;
    /**
     * Current network status code: Absent subscriber
     */
    public static final int MSG_NETWORK_NOTAUTH = -4;
    /**
     * Current network status code: Call barred
     */
    public static final int MSG_NETWORK_REFUSED = -5;
    /**
     * Current network status code: Teleservice not provisioned
     */
    public static final int MSG_NETWORK_NOTIMP = -6;
    /**
     * Current network status code: Facility not supported
     */
    public static final int MSG_NETWORK_YXRRSET = -7;
    /**
     * Current network status code: System failure
     */
    public static final int MSG_NETWORK_NXRRSET = -8;
    /**
     * Current network status code: CUG-reject
     */
    public static final int MSG_NETWORK_REJECTED = -9;
    /**
     * Current network status code: Invalid query format
     */
    public static final int MSG_NETWORK_QUERYERR = -10;
    /**
     * Current network status code: Timeout while processing
     */
    public static final int MSG_NETWORK_SRVFAIL = -11;
    /**
     * Current network status code: Not enough balance
     */
    public static final int MSG_NETWORK_NOTBALANCE = -12;
    /* The supported dlr states */
    public static final int DLR_STAT_DELIVRD = 1;
    public static final int DLR_STAT_EXPIRED = 2;
    public static final int DLR_STAT_DELETED = 3;
    public static final int DLR_STAT_UNDELIV = 4;
    public static final int DLR_STAT_ACCEPTD = 5;
    public static final int DLR_STAT_UNKNOWN = 6;
    public static final int DLR_STAT_REJECTD = 7;
    public static final int DLR_STAT_FAILED = 8;
    public static final int DLR_STAT_BUFFRED = 9;
    public static final int DLR_STAT_USER1 = 10;
    public static final int DLR_STAT_USER2 = 11;
    public static final int DLR_STAT_USER3 = 12;
    public static final int DLR_STAT_USER4 = 13;
    public static final int DLR_STAT_USER5 = 14;
    public static final int DLR_STAT_SEEN = 15;
    /* The supported dlr err codes */
    public static final int DLR_ERR_SUCCESS = 0;
    public static final int DLR_ERR_UNKNOWN_SUBSCR = 1;
    public static final int DLR_ERR_UNIDENT_SUBSCR = 2;
    public static final int DLR_ERR_ABSENT_SUBSCR = 3;
    public static final int DLR_ERR_BUSY_SUBSCR = 4;
    public static final int DLR_ERR_ILLEGAL_SUBSCR = 5;
    public static final int DLR_ERR_ILLEGAL_EQUIPMENT = 6;
    public static final int DLR_ERR_TELE_NOTPROV = 7;
    public static final int DLR_ERR_CALL_BARRED = 8;
    public static final int DLR_ERR_SYSTEM_FAIL = 9;
    public static final int DLR_ERR_DATA_MISS = 10;
    public static final int DLR_ERR_DATA_UNEXP = 11;
    public static final int DLR_ERR_MEMFULL = 12;
    public static final int DLR_ERR_TOUT_SENDING = 13;
    public static final int DLR_ERR_HSETMEM_EXCEEDED = 14;
    public static final int DLR_ERR_FACILITY_NOTSPRT = 15;
    public static final int DLR_ERR_RCVR_BLACKLISTED = 16;
    public static final int DLR_ERR_NO_RESPONSE = 17;
    public static final int DLR_ERR_UNKNOWN_ERROR = 18;
    public static final int DLR_ERR_SMS_REJECT = 19;
    public static final int DLR_ERR_SMS_DISCARD = 20;
    public static final int DLR_ERR_SMS_EXPIRED = 21;
    public static final int DLR_ERR_SMS_FAILED = 22;
    public static final int DLR_ERR_NO_BALANCE = 23;
    public static final int DLR_ERR_TEXT_PROHIBIT = 24;
    public static final int DLR_ERR_BILLING_ERR = 25;
    public static final int DLR_ERR_ROUTERR = 26;
    public static final int DLR_ERR_DELIVERY_FAIL = 27;
    public static final int DLR_ERR_SC_CONGESTION = 28;
    public static final int DLR_ERR_SC_UNKNOWN = 29;
    public static final int DLR_ERR_UNEXP_RESPONSE = 30;
    public static final int DLR_ERR_PROV_MALFUNCTION = 31;
    public static final int DLR_ERR_OPERATOR_ERROR = 32;
    public static final int DLR_ERR_RSC_LIMITATION = 33;
    public static final int DLR_ERR_AUTH_ERROR = 34;
    public static final int DLR_ERR_BILLED = 35;
    public static final int DLR_ERR_USER_NOTEXISTS = 36;
    public static final int DLR_ERR_AGE_RESTRICTION = 37;
    public static final int DLR_ERR_INVALID_PURCHASE = 38;
    public static final int DLR_ERR_NO_PRICE_SET = 39;
    public static final int DLR_ERR_NOTAUTH_PURCHASE = 40;
    public static final int DLR_ERR_PARENT_NO_BALANCE = 41;
    public static final int DLR_ERR_INVALID_AMOUNT = 42;
    public static final int DLR_ERR_INSUFFICIENT_BALANCE = 43;
    public static final int DLR_ERR_CHILD_NO_BALANCE = 44;
    public static final int DLR_ERR_MESSAGE_EXPIRED_INTERNALLY = 45;
    public static final int DLR_ERR_CREDIT_LIMIT_EXCEEDED = 46;
    public static final int DLR_ERR_OVERDRAFT_EXCEEDED = 47;
    /**
     * Message default state
     */
    public static final int MSG_STATE_DEFAULT = 0;
    public static final byte DCS_7BIT = 0x00;
    public static final byte DCS_8BIT = 0x04;
    public static final byte DCS_16BIT = 0x08;
    public static final int MSG_COST_PRECISION = 10000;

    public static final Map<String, Integer> fieldMap = ImmutableMap.<String, Integer>builder()
            .put("from", 1)
            .put("to", 2)
            .put("timestamp", 3)
            .put("type", 4)
            .put("body", 5)
            .put("serial", 6)
            .put("message_center", 7)
            .put("owner_id", 8)
            .put("priority", 9)
            .put("acked", 10)
            .put("expr", 11)
            .put("rtxCnt", 12)
            .put("binbody", 13)
            .put("binheader", 14)
            .put("flash", 15)
            .put("ttl", 16)
            .put("ddt", 17)
            .put("mclass", 20)
            .put("msgId", 21)
            .put("dcs", 24)
            .put("onetwork", 25)
            .put("cnetwork", 26)
            .put("ingateway", 27)
            .put("outgateway", 28)
            .put("state", 29)
            .put("cost", 30)
            .put("ctstamp", 31)
            .put("sutstamp", 32)
            .put("errcode", 33)
            .put("extrid", 34)
            .put("field1", 35)
            .put("field2", 36)
            .put("field3", 37)
            .put("field4", 38)
            .put("field5", 39)
            .put("field6", 40)
            .put("field7", 41)
            .put("field8", 42)
            .put("field9", 43)
            .put("field10", 44)
            .put("dispatchType", 47)
            .put("parts", 61)
            .put("paid", 63)
            .put("metadata", 64)
            .put("nextTarget", 66)
            .put("marginPercentage", 71)
            .put("marginStatus", 72)
            .build();

    public String from;
    public String to;
    public String timestamp;
    public int type;
    public String body;
    public String serial;
    public String message_center;
    public String owner_id;
    public String systemId;
    public int priority;
    public boolean acked;
    public int msgId;
    public long expr;
    public int rtxCnt;
    public String binbody;
    public String binheader;
    public boolean flash;
    public int mclass;
    public int ttl;
    public int ddt;
    public byte dcs;
    public int onetwork;
    public int cnetwork;
    public String ingateway;
    public String outgateway;
    public int state;
    public long cost;
    public long ctstamp;
    public long sutstamp;
    public String errcode;
    public String extrid;
    public String nextTarget;


    public Object field1, field2, field3, field4, field5, field6, field7, field8, field9, field10;

    public boolean paid;
    public String metadata;
    public int smsCnt;
    public int smsSubmitCnt;
    public Map<String, Object> attrs;
    public ArrayList<String> reassembledParts;
    public HashMap<String, String> tlvs;
    public int dispatchType;
    public String hlrRoute;
    public double marginPercentage;
    public MarginStatus marginStatus;

    public StandardMessage() {
        this("", "", "", MSG_TEXT, "", "", "", "", NORMAL_PRIORITY, false);
    }

    public StandardMessage(String frm, String to, String ts, int type, String body,
                           String srl, String center, String own, int priority, boolean acked) {
        long curr_tstamp = System.currentTimeMillis();

        this.from = frm;
        this.to = to;
        this.timestamp = ts;
        this.type = type;
        this.body = body;
        this.serial = srl;
        this.message_center = center;
        this.owner_id = own;
        this.priority = priority;
        this.acked = acked;

        expr = curr_tstamp;
        rtxCnt = 0;
        binbody = "";
        binheader = "";
        flash = false;
        ttl = -1;
        ddt = -1;
        mclass = 0;
        msgId = 0;
        dcs = DCS_7BIT;
        attrs = null;
        onetwork = MSG_NETWORK_NULL;
        cnetwork = MSG_NETWORK_NULL;
        ingateway = "";
        outgateway = "";
        state = MSG_STATE_DEFAULT;
        cost = MSG_COST_PRECISION;
        ctstamp = curr_tstamp;
        sutstamp = curr_tstamp;

        errcode = null;
        extrid = null;

        field1 = null;
        field2 = null;
        field3 = null;
        field4 = null;
        field5 = null;
        field6 = null;
        field7 = null;
        field8 = null;
        field9 = null;
        field10 = null;

        reassembledParts = null;
        systemId = null;
        this.marginStatus = MarginStatus.NOT_CALCULATED;
    }

    public StandardMessage(String pFrom, String pTo, String pText, int priority) {
        this.from = pFrom;
        this.to = pTo;
        this.body = pText;
        this.priority = priority;
        this.serial = "";
        this.marginStatus = MarginStatus.NOT_CALCULATED;
    }

    // --- Static Utility Methods ---

    public static boolean isCharDoubleInGsm(char character) {
        switch (character) {
            case '\f':  //FORM FEED
            case '^':  //CIRCUMFLEX ACCENT
            case '{':  //LEFT CURLY BRACKET
            case '}':  //RIGHT CURLY BRACKET
            case '\\':  //REVERSE SOLIDUS
            case '[':  //LEFT SQUARE BRACKET
            case '~':  //TILDE
            case ']':  //RIGHT SQUARE BRACKET
            case '|':  //VERTICAL LINE
            case '\u20ac': //EURO SIGN
                return true;
            default:
                return false;
        }
    }

    public static int getCharsCnt(String txt, int smtype) {
        int result = 0;
        if (smtype == MSG_TEXT || smtype == MSG_FLASH) {
            for (int i = 0; i < txt.length(); i++) {
                char character = txt.charAt(i);
                if (isCharDoubleInGsm(character)) {
                    result += 2;
                } else {
                    result++;
                }
            }
            return result;
        } else if (smtype == MSG_PUSH || smtype == MSG_BINARY) {
            return txt.length() / 2;
        } else {
            return txt.length();
        }
    }

    public static int getType(String type) {
        if (type != null) {
            switch (type) {
                case MSG_TYPE_TEXT: return MSG_TEXT;
                case MSG_TYPE_BINARY: return MSG_BINARY;
                case MSG_TYPE_BOOKMARK: return MSG_BOOKMARK;
                case MSG_TYPE_FLASH: return MSG_FLASH;
                case MSG_TYPE_PING: return MSG_PING;
                case MSG_TYPE_PUSH: return MSG_PUSH;
                case MSG_TYPE_UCS2: return MSG_UCS2;
                case MSG_TYPE_VCAL: return MSG_VCAL;
                case MSG_TYPE_VCARD: return MSG_VCARD;
                case MSG_TYPE_DLR: return MSG_DLR;
                case MSG_TYPE_HLR: return MSG_HLR;
                case MSG_TYPE_HLR_RSP: return MSG_HLR_RSP;
                case MSG_TYPE_MMS: return MSG_MMS;
                case MSG_TYPE_VIBER: return MSG_VIBER;
                case MSG_TYPE_DCB: return MSG_DCB;
                default:
                    throw new IllegalArgumentException("The type must me a valid, not custom, message type.");
            }
        }
        throw new IllegalArgumentException("The type must me a valid message type.");
    }

    public static String getType(int type) {
        switch (type) {
            case MSG_TEXT: return MSG_TYPE_TEXT;
            case MSG_BINARY: return MSG_TYPE_BINARY;
            case MSG_BOOKMARK: return MSG_TYPE_BOOKMARK;
            case MSG_FLASH: return MSG_TYPE_FLASH;
            case MSG_PING: return MSG_TYPE_PING;
            case MSG_PUSH: return MSG_TYPE_PUSH;
            case MSG_UCS2: return MSG_TYPE_UCS2;
            case MSG_VCAL: return MSG_TYPE_VCAL;
            case MSG_VCARD: return MSG_TYPE_VCARD;
            case MSG_DLR: return MSG_TYPE_DLR;
            case MSG_HLR: return MSG_TYPE_HLR;
            case MSG_HLR_RSP: return MSG_TYPE_HLR_RSP;
            case MSG_MMS: return MSG_TYPE_MMS;
            case MSG_VIBER: return MSG_TYPE_VIBER;
            case MSG_DCB: return MSG_TYPE_DCB;
            default: return MSG_TYPE_CUSTOM;
        }
    }

    // --- Core Methods ---

    public Object getAttr(String attrName) {
        if (attrs == null) {
            return null;
        }
        return attrs.get(attrName);
    }

    public Object putAttr(String attrName, Object attrValue) {
        if (attrs == null) {
            attrs = new HashMap<>(256);
        }
        return attrs.put(attrName, attrValue);
    }

    @Override
    public int compareTo(StandardMessage m) {
        return (int) (expr - m.expr);
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        return super.clone();
    }

    public boolean equals(Object o) throws ClassCastException {
        return equals((StandardMessage) o);
    }

    public int hashCode() {
        return serial.hashCode();
    }

    public boolean equals(StandardMessage msg) {
        return serial.equals(msg.serial);
    }

    public boolean isReassembledMessage() {
        return reassembledParts != null && !reassembledParts.isEmpty();
    }

    public String getTlv(String tag) {
        return tlvs == null ? null : tlvs.get(tag);
    }

    public Map<String, Integer> getFieldMap() {
        return fieldMap;
    }

    public void setTlv(String tag, String value) {
        if (tlvs == null) {
            tlvs = new HashMap<>();
        }
        tlvs.put(tag, value);
    }

    public StandardMessage createResponse(String pFrom, String pTo, String pText, int pPriority) {
        StandardMessage msg;
        try {
            msg = (StandardMessage) this.clone();
        } catch (CloneNotSupportedException e) {
            logger.error("clone not supported", e);
            return null;
        }
        msg.extrid = this.extrid;
        msg.from = pFrom;
        msg.to = pTo;
        msg.body = pText;
        msg.priority = pPriority;
        msg.onetwork = this.onetwork;
        msg.cnetwork = this.cnetwork;
        msg.message_center = this.message_center;

        if (!Strings.isNullOrEmpty(this.serial)) {
            msg.serial = UUID.randomUUID().toString();
        }

        return msg;
    }

    // --- Dynamic Value Accessors (Core Fields) ---

    public void setValue(int pidx, String pvalue) throws IllegalArgumentException {
        switch (pidx) {
            case 1: from = pvalue; break;
            case 2: to = pvalue; break;
            case 3: timestamp = pvalue; break;
            case 4: type = Integer.parseInt(pvalue); break;
            case 5: body = pvalue; break;
            case 6: serial = pvalue; break;
            case 7: message_center = pvalue; break;
            case 8: owner_id = pvalue; break;
            case 9: priority = Integer.parseInt(pvalue); break;
            case 10: acked = Boolean.parseBoolean(pvalue); break;
            case 11: expr = Long.parseLong(pvalue); break;
            case 12: rtxCnt = Integer.parseInt(pvalue); break;
            case 13: binbody = pvalue; break;
            case 14: binheader = pvalue; break;
            case 15: flash = Boolean.parseBoolean(pvalue); break;
            case 16: ttl = Integer.parseInt(pvalue); break;
            case 17: ddt = Integer.parseInt(pvalue); break;
            case 20: mclass = Integer.parseInt(pvalue); break;
            case 21: msgId = Integer.parseInt(pvalue); break;
            case 24: dcs = Byte.parseByte(pvalue); break;
            case 25: onetwork = Integer.parseInt(pvalue); break;
            case 26: cnetwork = Integer.parseInt(pvalue); break;
            case 27: ingateway = pvalue; break;
            case 28: outgateway = pvalue; break;
            case 29: state = Integer.parseInt(pvalue); break;
            case 30: cost = Long.parseLong(pvalue); break;
            case 31: ctstamp = Long.parseLong(pvalue); break;
            case 32: sutstamp = Long.parseLong(pvalue); break;
            case 33: errcode = pvalue; break;
            case 34: extrid = pvalue; break;
            case 35: field1 = pvalue; break;
            case 36: field2 = pvalue; break;
            case 37: field3 = pvalue; break;
            case 38: field4 = pvalue; break;
            case 39: field5 = pvalue; break;
            case 40: field6 = pvalue; break;
            case 41: field7 = pvalue; break;
            case 42: field8 = pvalue; break;
            case 43: field9 = pvalue; break;
            case 44: field10 = pvalue; break;
            case 47: dispatchType = Integer.parseInt(pvalue); break;
            case 61: reassembledParts = pvalue != null ? new ArrayList<>(Arrays.asList(pvalue.split(","))) : null; break;
            case 63: paid = Boolean.parseBoolean(pvalue); break;
            case 66: nextTarget = pvalue; break;
            case 64: metadata = pvalue; break;
            case 71: marginPercentage = Double.parseDouble(pvalue); break;
            case 72: marginStatus = MarginStatus.valueOf(pvalue); break;
            default: throw new IllegalArgumentException("Unknown field number: " + pidx);
        }
    }

    public String getValue(int pidx) throws IllegalArgumentException {
        switch (pidx) {
            case 1: return from;
            case 2: return to;
            case 3: return timestamp;
            case 4: return Integer.toString(type);
            case 5: return body;
            case 6: return serial;
            case 7: return message_center;
            case 8: return owner_id;
            case 9: return Integer.toString(priority);
            case 10: return Boolean.toString(acked);
            case 11: return Long.toString(expr);
            case 12: return Integer.toString(rtxCnt);
            case 13: return binbody;
            case 14: return binheader;
            case 15: return Boolean.toString(flash);
            case 16: return Integer.toString(ttl);
            case 17: return Integer.toString(ddt);
            case 20: return Integer.toString(mclass);
            case 21: return Integer.toString(msgId);
            case 24: return Byte.toString(dcs);
            case 25: return Integer.toString(onetwork);
            case 26: return Integer.toString(cnetwork);
            case 27: return ingateway;
            case 28: return outgateway;
            case 29: return Integer.toString(state);
            case 30: return Long.toString(cost);
            case 31: return Long.toString(ctstamp);
            case 32: return Long.toString(sutstamp);
            case 33: return errcode;
            case 34: return extrid;
            case 35: return String.valueOf(field1);
            case 36: return String.valueOf(field2);
            case 37: return String.valueOf(field3);
            case 38: return String.valueOf(field4);
            case 39: return String.valueOf(field5);
            case 40: return String.valueOf(field6);
            case 41: return String.valueOf(field7);
            case 42: return String.valueOf(field8);
            case 43: return String.valueOf(field9);
            case 44: return String.valueOf(field10);
            case 47: return Integer.toString(dispatchType);
            case 61: return String.join(",", reassembledParts);
            case 63: return String.valueOf(paid);
            case 64: return metadata;
            case 66: return nextTarget;
            case 71: return String.valueOf(marginPercentage);
            case 72: return marginStatus != null ? marginStatus.name() : null;
            default: throw new IllegalArgumentException("Unknown field number: " + pidx);
        }
    }

    public float getFloatValue(int pidx) throws IllegalArgumentException {
        try {
            switch (pidx) {
                case 35: return (Float) field1;
                case 36: return (Float) field2;
                case 37: return (Float) field3;
                case 38: return (Float) field4;
                case 39: return (Float) field5;
                case 40: return (Float) field6;
                case 41: return (Float) field7;
                case 42: return (Float) field8;
                case 43: return (Float) field9;
                case 44: return (Float) field10;
            }
        } catch (Exception e) {
            logger.warn("error converting field {} to float", pidx);
        }
        throw new IllegalArgumentException("Field number: " + pidx + " is not float");
    }

    public double getDoubleValue(int pidx) throws IllegalArgumentException {
        try {
            switch (pidx) {
                case 35: return ((Number) field1).doubleValue();
                case 36: return ((Number) field2).doubleValue();
                case 37: return ((Number) field3).doubleValue();
                case 38: return ((Number) field4).doubleValue();
                case 39: return ((Number) field5).doubleValue();
                case 40: return ((Number) field6).doubleValue();
                case 41: return ((Number) field7).doubleValue();
                case 42: return ((Number) field8).doubleValue();
                case 43: return ((Number) field9).doubleValue();
                case 44: return ((Number) field10).doubleValue();
                case 71: return marginPercentage;
            }
        } catch (Exception e) {
            logger.warn("error converting field {} to double", pidx);
        }
        throw new IllegalArgumentException("Field number: " + pidx + " is not double");
    }


    public char getCharValue(int pidx) throws IllegalArgumentException {
        try {
            switch (pidx) {
                case 35: return (Character) field1;
                case 36: return (Character) field2;
                case 37: return (Character) field3;
                case 38: return (Character) field4;
                case 39: return (Character) field5;
                case 40: return (Character) field6;
                case 41: return (Character) field7;
                case 42: return (Character) field8;
                case 43: return (Character) field9;
                case 44: return (Character) field10;
            }
        } catch (Exception e) {
            logger.warn("error converting field {} to char", pidx);
        }
        throw new IllegalArgumentException("Field number: " + pidx + " is not char");
    }

    public byte getByteValue(int pidx) throws IllegalArgumentException {
        try {
            switch (pidx) {
                case 24: return dcs;
                case 35: return (Byte) field1;
                case 36: return (Byte) field2;
                case 37: return (Byte) field3;
                case 38: return (Byte) field4;
                case 39: return (Byte) field5;
                case 40: return (Byte) field6;
                case 41: return (Byte) field7;
                case 42: return (Byte) field8;
                case 43: return (Byte) field9;
                case 44: return (Byte) field10;
            }
        } catch (Exception e) {
            logger.warn("error converting field {} to byte", pidx);
        }
        throw new IllegalArgumentException("Field number: " + pidx + " is not byte");
    }

    public short getShortValue(int pidx) throws IllegalArgumentException {
        try {
            switch (pidx) {
                case 35: return (Short) field1;
                case 36: return (Short) field2;
                case 37: return (Short) field3;
                case 38: return (Short) field4;
                case 39: return (Short) field5;
                case 40: return (Short) field6;
                case 41: return (Short) field7;
                case 42: return (Short) field8;
                case 43: return (Short) field9;
                case 44: return (Short) field10;
            }
        } catch (Exception e) {
            logger.warn("error converting field {} to short", pidx);
        }
        throw new IllegalArgumentException("Field number: " + pidx + " is not short");
    }

    public long getLongValue(int pidx) throws IllegalArgumentException {
        try {
            switch (pidx) {
                case 11: return expr;
                case 30: return cost;
                case 31: return ctstamp;
                case 32: return sutstamp;
                case 35: return (Long) field1;
                case 36: return (Long) field2;
                case 37: return (Long) field3;
                case 38: return (Long) field4;
                case 39: return (Long) field5;
                case 40: return (Long) field6;
                case 41: return (Long) field7;
                case 42: return (Long) field8;
                case 43: return (Long) field9;
                case 44: return (Long) field10;
            }
        } catch (Exception e) {
            logger.warn("error converting field {} to byte", pidx);
        }
        throw new IllegalArgumentException("Field number: " + pidx + " is not long");
    }

    public boolean getBooleanValue(int pidx) throws IllegalArgumentException {
        try {
            switch (pidx) {
                case 10: return acked;
                case 15: return flash;
                case 35: return (Boolean) field1;
                case 36: return (Boolean) field2;
                case 37: return (Boolean) field3;
                case 38: return (Boolean) field4;
                case 39: return (Boolean) field5;
                case 40: return (Boolean) field6;
                case 41: return (Boolean) field7;
                case 42: return (Boolean) field8;
                case 43: return (Boolean) field9;
                case 44: return (Boolean) field10;
                case 63: return paid;
            }
        } catch (Exception e) {
            logger.warn("error converting field {} to boolean", pidx);
        }
        throw new IllegalArgumentException("Field number: " + pidx + " is not boolean");
    }

    public int getIntValue(int pidx) throws IllegalArgumentException {
        try {
            switch (pidx) {
                case 4: return type;
                case 9: return priority;
                case 12: return rtxCnt;
                case 16: return ttl;
                case 17: return ddt;
                case 20: return mclass;
                case 21: return msgId;
                case 25: return onetwork;
                case 26: return cnetwork;
                case 29: return state;
                case 35: return (Integer) field1;
                case 36: return (Integer) field2;
                case 37: return (Integer) field3;
                case 38: return (Integer) field4;
                case 39: return (Integer) field5;
                case 40: return (Integer) field6;
                case 41: return (Integer) field7;
                case 42: return (Integer) field8;
                case 43: return (Integer) field9;
                case 44: return (Integer) field10;
                case 47: return dispatchType;
                case 71: return (int) Math.round(marginPercentage);
                case 72: return marginStatus.ordinal();
            }
        } catch (Exception e) {
            logger.warn("error converting field {} to byte", pidx);
        }
        throw new IllegalArgumentException("Field number: " + pidx + " is not int");
    }

    public String toString() {
        return "msg.T{0}.id{27}.u{14}.p{1}.f{2}.t{3}.b{5}.o{6}.c{7}.ig{8}.og{9}.s{10}.e{15}.m{31}.nt{33}.r{42}"
                .replace("{0}", String.valueOf(type)).replace("{1}", String.valueOf(priority)).replace("{2}", String.valueOf(from))
                .replace("{3}", String.valueOf(to)).replace("{5}", String.valueOf(body))
                .replace("{6}", String.valueOf(onetwork)).replace("{7}", String.valueOf(cnetwork)).replace("{8}", String.valueOf(ingateway))
                .replace("{9}", String.valueOf(outgateway)).replace("{10}", String.valueOf(state)).replace("{11}", String.valueOf(cost))
                .replace("{12}", String.valueOf(ctstamp)).replace("{13}", String.valueOf(sutstamp)).replace("{14}", String.valueOf(owner_id))
                .replace("{15}", String.valueOf(errcode)).replace("{16}", String.valueOf(extrid)).replace("{17}", String.valueOf(field1))
                .replace("{18}", String.valueOf(field2)).replace("{19}", String.valueOf(field3)).replace("{20}", String.valueOf(field4))
                .replace("{21}", String.valueOf(field5)).replace("{22}", String.valueOf(field6)).replace("{23}", String.valueOf(field7))
                .replace("{24}", String.valueOf(field8)).replace("{25}", String.valueOf(field9)).replace("{26}", String.valueOf(field10))
                .replace("{27}", String.valueOf(msgId)).replace("{28}", String.valueOf(reassembledParts))
                .replace("{30}", String.valueOf(paid)).replace("{31}", String.valueOf(metadata))
                .replace("{33}", String.valueOf(nextTarget))
                .replace("{39}", String.valueOf(serial))
                .replace("{42}", String.valueOf(rtxCnt))
                ;
    }

    public enum MarginStatus {
        NOT_CALCULATED, // 0 - Default, Router skipped it
        KNOWN,          // 1 - Calculated successfully
        UNKNOWN         // 2 - Calculation failed (missing data)
    }
}
