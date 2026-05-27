package gr.cytech.sendium.core.smpp.util;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.commons.util.HexUtil;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.pdu.GenericNack;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import gr.cytech.sendium.core.message.StandardMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmppServerUtilTest {

    @Test
    void encodeAndDecodeFinalStateMapsKnownStates() {
        assertThat(SmppServerUtil.encodeFinalState(StandardMessage.DLR_STAT_DELIVRD))
                .isEqualTo(SmppConstants.STATE_DELIVERED);
        assertThat(SmppServerUtil.encodeFinalState(StandardMessage.DLR_STAT_FAILED))
                .isEqualTo(SmppConstants.STATE_UNDELIVERABLE);
        assertThat(SmppServerUtil.encodeFinalState(StandardMessage.DLR_STAT_BUFFRED))
                .isEqualTo(SmppConstants.STATE_ENROUTE);
        assertThat(SmppServerUtil.decodeFinalState(SmppConstants.STATE_REJECTED))
                .isEqualTo(StandardMessage.DLR_STAT_REJECTD);
        assertThat(SmppServerUtil.decodeFinalState((byte) 0x7F))
                .isEqualTo(StandardMessage.DLR_STAT_FAILED);
    }

    @Test
    void createSubmitRspCopiesStatusMessageIdAndReferenceObject() {
        SubmitSm request = new SubmitSm();
        Object reference = new Object();
        request.setReferenceObject(reference);

        SubmitSmResp response = SmppServerUtil.createSubmitRsp(request, SmppConstants.STATUS_THROTTLED, "msg-1");

        assertThat(response.getCommandStatus()).isEqualTo(SmppConstants.STATUS_THROTTLED);
        assertThat(response.getMessageId()).isEqualTo("msg-1");
        assertThat(response.getReferenceObject()).isSameAs(reference);
    }

    @Test
    void createSubmitRspRejectsNullRequest() {
        assertThatThrownBy(() -> SmppServerUtil.createSubmitRsp(null, SmppConstants.STATUS_OK, "msg-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createGenericNackCopiesStatusAndReferenceObject() {
        SubmitSm request = new SubmitSm();
        Object reference = new Object();
        request.setReferenceObject(reference);

        GenericNack nack = SmppServerUtil.createGenericNack(request, SmppConstants.STATUS_INVCMDID);

        assertThat(nack.getCommandStatus()).isEqualTo(SmppConstants.STATUS_INVCMDID);
        assertThat(nack.getReferenceObject()).isSameAs(reference);
    }

    @Test
    void splitMessageReturnsSingleSegmentWhenBodyFits() {
        byte[][] parts = SmppServerUtil.splitMessage("a".repeat(160), CharsetUtil.NAME_GSM, 0x2A, true);

        assertThat(parts.length).isEqualTo(1);
        assertThat(CharsetUtil.decode(parts[0], CharsetUtil.NAME_GSM)).isEqualTo("a".repeat(160));
    }

    @Test
    void splitMessageAdds8BitUdhForMultipartGsmMessages() {
        byte[][] parts = SmppServerUtil.splitMessage("a".repeat(161), CharsetUtil.NAME_GSM, 0x2A, true);

        assertThat(parts.length).isEqualTo(2);
        assertThat(HexUtil.toHexString(parts[0], 0, 6)).isEqualTo("0500032A0201");
        assertThat(HexUtil.toHexString(parts[1], 0, 6)).isEqualTo("0500032A0202");
        assertThat(parts[0]).hasSize(159);
        assertThat(parts[1]).hasSize(14);
    }

    @Test
    void splitMessageAdds16BitUdhWhenConfigured() {
        byte[][] parts = SmppServerUtil.splitMessage("a".repeat(161), CharsetUtil.NAME_GSM, 0x1234, false);

        assertThat(parts.length).isEqualTo(2);
        assertThat(HexUtil.toHexString(parts[0], 0, 7)).isEqualTo("06080412340201");
        assertThat(HexUtil.toHexString(parts[1], 0, 7)).isEqualTo("06080412340202");
    }

    @Test
    void splitMessageToBodyAndUdhSeparatesHeaderFromPayload() {
        byte[] data = new byte[]{0x05, 0x00, 0x03, 0x01, 0x02, 0x01, 'H', 'i'};

        byte[][] split = SmppServerUtil.splitMessageToBodyAndUdh(data);

        assertThat(HexUtil.toHexString(split[1])).isEqualTo("050003010201");
        assertThat(new String(split[0])).isEqualTo("Hi");
    }

    @Test
    void mergeByteArraysAppendsSecondArray() {
        byte[] merged = SmppServerUtil.mergeByteArrays(new byte[]{0x01, 0x02}, new byte[]{0x03});

        assertThat(merged).containsExactly((byte) 0x01, (byte) 0x02, (byte) 0x03);
    }

    @Test
    void encodeErrorCodeFallsBackForUnknownErrors() {
        assertThat(SmppServerUtil.encodeErrorCode(StandardMessage.DLR_ERR_SUCCESS))
                .isEqualTo(StandardMessage.DLR_ERR_SUCCESS);
        assertThat(SmppServerUtil.encodeErrorCode(9999))
                .isEqualTo(StandardMessage.DLR_ERR_UNKNOWN_ERROR);
    }
}
