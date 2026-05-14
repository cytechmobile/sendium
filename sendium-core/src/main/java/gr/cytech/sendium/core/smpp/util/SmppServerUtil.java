package gr.cytech.sendium.core.smpp.util;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.pdu.GenericNack;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import gr.cytech.sendium.core.message.StandardMessage;

public class SmppServerUtil {

    public static final int MAX_MULTIPART_MSG_SEGMENT_SIZE_UCS2 = 134;
    public static final int MAX_SINGLE_MSG_SEGMENT_SIZE_UCS2 = 140;
    public static final int MAX_MULTIPART_MSG_SEGMENT_SIZE_7BIT = 153;
    public static final int MAX_SINGLE_MSG_SEGMENT_SIZE_7BIT = 160;

    public static byte encodeFinalState(int state) {
        switch (state) {
            case StandardMessage.DLR_STAT_DELIVRD:
                return SmppConstants.STATE_DELIVERED;
            case StandardMessage.DLR_STAT_EXPIRED:
                return SmppConstants.STATE_EXPIRED;
            case StandardMessage.DLR_STAT_DELETED:
                return SmppConstants.STATE_DELETED;
            case StandardMessage.DLR_STAT_UNDELIV:
            case StandardMessage.DLR_STAT_FAILED:
                return SmppConstants.STATE_UNDELIVERABLE;
            case StandardMessage.DLR_STAT_ACCEPTD:
                return SmppConstants.STATE_ACCEPTED;
            case StandardMessage.DLR_STAT_REJECTD:
                return SmppConstants.STATE_REJECTED;
            case StandardMessage.DLR_STAT_BUFFRED:
                return SmppConstants.STATE_ENROUTE;
            case StandardMessage.DLR_STAT_UNKNOWN:
            default:
                return SmppConstants.STATE_UNKNOWN;
        }
    }

    public static int decodeFinalState(byte state) {
        switch (state) {
            case SmppConstants.STATE_DELIVERED:
                return StandardMessage.DLR_STAT_DELIVRD;
            case SmppConstants.STATE_EXPIRED:
                return StandardMessage.DLR_STAT_EXPIRED;
            case SmppConstants.STATE_DELETED:
                return StandardMessage.DLR_STAT_DELETED;
            case SmppConstants.STATE_UNDELIVERABLE:
                return StandardMessage.DLR_STAT_UNDELIV;
            case SmppConstants.STATE_ACCEPTED:
                return StandardMessage.DLR_STAT_ACCEPTD;
            case SmppConstants.STATE_REJECTED:
                return StandardMessage.DLR_STAT_REJECTD;
            case SmppConstants.STATE_ENROUTE:
                return StandardMessage.DLR_STAT_BUFFRED;
            case SmppConstants.STATE_UNKNOWN:
            default:
                return StandardMessage.DLR_STAT_FAILED;
        }
    }

    public static SubmitSmResp createSubmitRsp(SubmitSm req, int status, String msgid) {
        if (req == null) {
            throw new IllegalArgumentException();
        }
        SubmitSmResp resp = req.createResponse();
        resp.setMessageId(msgid);
        resp.setReferenceObject(req.getReferenceObject());
        resp.setCommandStatus(status);
        return resp;
    }

    public static GenericNack createGenericNack(PduRequest<?> req, int status) {
        if (req == null) {
            throw new IllegalArgumentException("cannot create generic nack for null request");
        }

        GenericNack nack = req.createGenericNack(status);
        nack.setReferenceObject(req.getReferenceObject());
        return nack;
    }

    public static String getMessageBody(byte[] msg, String charset) {
        return CharsetUtil.decode(msg, charset);
    }

    public static String getMessageBody(byte[] msg) {
        return getMessageBody(msg, "UTF8");
    }

    public static int encodeErrorCode(int errcode) {
        switch (errcode) {
            case StandardMessage.DLR_ERR_SUCCESS:
            case StandardMessage.DLR_ERR_ABSENT_SUBSCR:
            case StandardMessage.DLR_ERR_BUSY_SUBSCR:
            case StandardMessage.DLR_ERR_CALL_BARRED:
            case StandardMessage.DLR_ERR_DATA_MISS:
            case StandardMessage.DLR_ERR_DATA_UNEXP:
            case StandardMessage.DLR_ERR_FACILITY_NOTSPRT:
            case StandardMessage.DLR_ERR_MEMFULL:
            case StandardMessage.DLR_ERR_HSETMEM_EXCEEDED:
            case StandardMessage.DLR_ERR_ILLEGAL_EQUIPMENT:
            case StandardMessage.DLR_ERR_ILLEGAL_SUBSCR:
            case StandardMessage.DLR_ERR_NO_RESPONSE:
            case StandardMessage.DLR_ERR_RCVR_BLACKLISTED:
            case StandardMessage.DLR_ERR_SMS_DISCARD:
            case StandardMessage.DLR_ERR_SMS_EXPIRED:
            case StandardMessage.DLR_ERR_MESSAGE_EXPIRED_INTERNALLY:
            case StandardMessage.DLR_ERR_SMS_FAILED:
            case StandardMessage.DLR_ERR_SMS_REJECT:
            case StandardMessage.DLR_ERR_SYSTEM_FAIL:
            case StandardMessage.DLR_ERR_TELE_NOTPROV:
            case StandardMessage.DLR_ERR_TOUT_SENDING:
            case StandardMessage.DLR_ERR_UNIDENT_SUBSCR:
            case StandardMessage.DLR_ERR_UNKNOWN_SUBSCR:
            case StandardMessage.DLR_ERR_NO_BALANCE:
            case StandardMessage.DLR_ERR_TEXT_PROHIBIT:
            case StandardMessage.DLR_ERR_BILLING_ERR:
            case StandardMessage.DLR_ERR_ROUTERR:
            case StandardMessage.DLR_ERR_DELIVERY_FAIL:
            case StandardMessage.DLR_ERR_SC_CONGESTION:
            case StandardMessage.DLR_ERR_SC_UNKNOWN:
            case StandardMessage.DLR_ERR_UNEXP_RESPONSE:
            case StandardMessage.DLR_ERR_PROV_MALFUNCTION:
            case StandardMessage.DLR_ERR_OPERATOR_ERROR:
            case StandardMessage.DLR_ERR_RSC_LIMITATION:
            case StandardMessage.DLR_ERR_AUTH_ERROR:
            case StandardMessage.DLR_ERR_INVALID_AMOUNT:
            case StandardMessage.DLR_ERR_INSUFFICIENT_BALANCE:
                return errcode;
            default:
                return StandardMessage.DLR_ERR_UNKNOWN_ERROR;
        }
    }

    /**
     * Converts the given string into a byte array using the provided charset and then splits the message into parts
     * using the correct UDH for each message part
     *
     * @param messageBody   the original message body
     * @param charSet       the charset to use to decode the body into bytes
     * @param messageRefNum the message reference number to use for the split parts
     * @param ccat8bit      whether 8-bit concatenation (if true) or 16-bit concatenation (if false) will be used
     * @return the split message parts in separate byte arrays
     */
    public static byte[][] splitMessage(String messageBody, String charSet, int messageRefNum, boolean ccat8bit) {
        int maximumSingleMessageSize;
        int maximumMultipartMessageSegmentSize;
        byte[] byteSingleMessage = CharsetUtil.encode(messageBody, charSet);
        if (!CharsetUtil.NAME_UCS_2.equals(charSet) && !"ISO-10646-UCS-2".equals(charSet)) {
            maximumSingleMessageSize = MAX_SINGLE_MSG_SEGMENT_SIZE_7BIT;
            maximumMultipartMessageSegmentSize = MAX_MULTIPART_MSG_SEGMENT_SIZE_7BIT;
        } else {
            maximumSingleMessageSize = MAX_SINGLE_MSG_SEGMENT_SIZE_UCS2;
            maximumMultipartMessageSegmentSize = MAX_MULTIPART_MSG_SEGMENT_SIZE_UCS2;
        }

        byte[][] byteMessagesArray;
        if (byteSingleMessage.length > maximumSingleMessageSize) {
            // split message according to the maximum length of a segment
            byteMessagesArray = splitMessage(byteSingleMessage, messageRefNum, maximumMultipartMessageSegmentSize, ccat8bit);
        } else {
            byteMessagesArray = new byte[][]{byteSingleMessage};
        }

        return byteMessagesArray;
    }

    /**
     * Splits a message to multiple parts with correct UDH in the start of the body
     *
     * @param message       the original message that will be split to multiple parts
     * @param messageRefNum the message reference number that all parts will refer to
     * @param ccat8bit      whether 8-bit concatenation header will be used (otherwise 16-bit concatenation header will be used)
     * @return the split message parts in separate byte arrays
     */
    private static byte[][] splitMessage(byte[] message, int messageRefNum, int maximumMultipartMessageSegmentSize, boolean ccat8bit) {
        byte udhieHeaderLength;
        byte udhieIdentifierSar;
        byte udhieSarLength;
        byte[] referenceNumber;

        // generate udh data and reference number
        if (ccat8bit) {
            udhieHeaderLength = 0x05;
            udhieIdentifierSar = 0x00;
            udhieSarLength = 0x03;
            referenceNumber = copyShortByte(messageRefNum);
        } else {
            udhieHeaderLength = 0x06;
            udhieIdentifierSar = 0x08;
            udhieSarLength = 0x04;
            referenceNumber = copyShort2Bytes(messageRefNum);
        }

        // determine how many messages have to be sent
        int numberOfSegments = message.length / maximumMultipartMessageSegmentSize;
        int messageLength = message.length;
        if (numberOfSegments > 255) {
            numberOfSegments = 255;
            messageLength = numberOfSegments * maximumMultipartMessageSegmentSize;
        }
        if ((messageLength % maximumMultipartMessageSegmentSize) > 0) {
            numberOfSegments++;
        }

        // prepare array for all of the msg segments
        byte[][] segments = new byte[numberOfSegments][];

        int lengthOfData;

        // split the message adding required headers
        for (int i = 0; i < numberOfSegments; i++) {
            if (numberOfSegments - i == 1) {
                lengthOfData = messageLength - i * maximumMultipartMessageSegmentSize;
            } else {
                lengthOfData = maximumMultipartMessageSegmentSize;
            }

            // new array to store the header
            segments[i] = new byte[(ccat8bit ? 6 : 7) + lengthOfData];

            // UDH header
            // doesn't include itself, its header length
            segments[i][0] = udhieHeaderLength;
            // SAR identifier
            segments[i][1] = udhieIdentifierSar;
            // SAR length
            segments[i][2] = udhieSarLength;
            // reference number (same for all message parts)
            int segmentIdx = 3;
            for (byte ref : referenceNumber) {
                segments[i][segmentIdx++] = ref;
            }
            // total number of segments
            segments[i][segmentIdx++] = (byte) numberOfSegments;
            // segment number
            segments[i][segmentIdx++] = (byte) (i + 1);

            // copy the data into the array
            System.arraycopy(message, (i * maximumMultipartMessageSegmentSize), segments[i], segmentIdx, lengthOfData);

        }
        return segments;
    }

    public static byte[] copyShort2Bytes(int integer) {
        byte[] bytes = new byte[2];
        bytes[0] = (byte) ((integer >> 8) & 0x0000ff);
        bytes[1] = (byte) (integer & 0x000000ff);

        return bytes;
    }

    public static byte[] copyShortByte(int integer) {
        return new byte[]{(byte) (integer & 0x000000ff)};
    }

    public static byte[] mergeByteArrays(byte[] first, byte[] second) {
        byte[] res = new byte[first.length + second.length];
        System.arraycopy(first, 0, res, 0, first.length);
        System.arraycopy(second, 0, res, first.length, second.length);
        return res;
    }

    public static byte[][] splitMessageToBodyAndUdh(byte[] data) {
        int udhLength = (int) data[0];
        byte[] udhBytes = new byte[udhLength + 1];
        System.arraycopy(data, 0, udhBytes, 0, udhBytes.length);
        byte[] bodyBytes = new byte[data.length - udhBytes.length];
        System.arraycopy(data, udhBytes.length, bodyBytes, 0, bodyBytes.length);

        return new byte[][]{bodyBytes, udhBytes};
    }

    public record ValidatedMessageBody(String text, String udh, int smType) {
    }
}
