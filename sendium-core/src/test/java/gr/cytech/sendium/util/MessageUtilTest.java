package gr.cytech.sendium.util;

import gr.cytech.sendium.core.message.StandardMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageUtilTest {

    @Test
    void udhHelpersIdentify8BitConcatenatedMessageParts() {
        StandardMessage message = messageWithUdh("0500037F0201");
        message.owner_id = "account-a";

        assertThat(MessageUtil.hasUdh(message)).isTrue();
        assertThat(MessageUtil.is8BitMessagePart(message)).isTrue();
        assertThat(MessageUtil.is16BitMessagePart(message)).isFalse();
        assertThat(MessageUtil.getMessageReference(message)).isEqualTo("account-a:7F");
        assertThat(MessageUtil.getNumberOfTotalParts(message)).isEqualTo(2);
        assertThat(MessageUtil.getNumberOfCurrentPart(message)).isEqualTo(1);
    }

    @Test
    void udhHelpersIdentify16BitConcatenatedMessageParts() {
        StandardMessage message = messageWithUdh("06080412340302");
        message.owner_id = "account-b";

        assertThat(MessageUtil.is16BitMessagePart(message)).isTrue();
        assertThat(MessageUtil.is8BitMessagePart(message)).isFalse();
        assertThat(MessageUtil.getMessageReference(message)).isEqualTo("account-b:1234");
        assertThat(MessageUtil.getNumberOfTotalParts(message)).isEqualTo(3);
        assertThat(MessageUtil.getNumberOfCurrentPart(message)).isEqualTo(2);
    }

    @Test
    void udhHelpersReturnDefaultsWhenNoSupportedUdhExists() {
        StandardMessage message = new StandardMessage();
        message.owner_id = "account";

        assertThat(MessageUtil.hasUdh(message)).isFalse();
        assertThat(MessageUtil.getMessageReference(message)).isNull();
        assertThat(MessageUtil.getNumberOfTotalParts(message)).isZero();
        assertThat(MessageUtil.getNumberOfCurrentPart(message)).isZero();
    }

    @Test
    void getSmsCntUsesTextAndConcatenationBoundaries() {
        assertThat(MessageUtil.getSmsCnt("a".repeat(160), StandardMessage.MSG_TEXT, true)).isEqualTo(1);
        assertThat(MessageUtil.getSmsCnt("a".repeat(161), StandardMessage.MSG_TEXT, true)).isEqualTo(2);
        assertThat(MessageUtil.getSmsCnt("a".repeat(306), StandardMessage.MSG_TEXT, true)).isEqualTo(2);
        assertThat(MessageUtil.getSmsCnt("a".repeat(307), StandardMessage.MSG_TEXT, true)).isEqualTo(3);
    }

    @Test
    void getSmsCntUsesUcs2AndPushBoundaries() {
        assertThat(MessageUtil.getSmsCnt("a".repeat(70), StandardMessage.MSG_UCS2, true)).isEqualTo(1);
        assertThat(MessageUtil.getSmsCnt("a".repeat(71), StandardMessage.MSG_UCS2, true)).isEqualTo(2);
        assertThat(MessageUtil.getSmsCnt("a".repeat(134), StandardMessage.MSG_UCS2, true)).isEqualTo(2);
        assertThat(MessageUtil.getSmsCnt("a".repeat(268), StandardMessage.MSG_PUSH, false)).isEqualTo(-1);
        assertThat(MessageUtil.getSmsCnt("a".repeat(268), StandardMessage.MSG_PUSH, true)).isEqualTo(2);
    }

    @Test
    void getSmsCntAndTrimForUdhTrimsTextToSingleSmsCapacity() {
        StandardMessage message = messageWithUdh("050003010201");
        message.type = StandardMessage.MSG_TEXT;
        message.body = "a".repeat(160);

        int smsCount = MessageUtil.getSmsCntAndTrimForUdh(message, true);

        assertThat(smsCount).isEqualTo(1);
        assertThat(message.body).hasSize(153);
    }

    @Test
    void getSmsCntAndTrimForUdhTrimsBinaryHexBodyToSingleSmsCapacity() {
        StandardMessage message = messageWithUdh("050003010201");
        message.type = StandardMessage.MSG_BINARY;
        message.body = "A".repeat(300);

        int smsCount = MessageUtil.getSmsCntAndTrimForUdh(message, true);

        assertThat(smsCount).isEqualTo(1);
        assertThat(message.body).hasSize(268);
    }

    @Test
    void validateSmsLengthCountsEscapedGsmCharactersWhenTrimming() {
        String result = MessageUtil.validateSmsLength(null, "{}A", StandardMessage.MSG_TEXT, 3);

        assertThat(result).isEqualTo("{");
    }

    @Test
    void validateTextNormalizesGreekForTextMessages() {
        String result = MessageUtil.validateText(null, "άβ", StandardMessage.MSG_TEXT, 160);

        assertThat(result).isEqualTo("ΑΒ");
    }

    private StandardMessage messageWithUdh(String udh) {
        StandardMessage message = new StandardMessage();
        message.binheader = udh;
        return message;
    }
}
