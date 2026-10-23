package gr.cytech.sendium.util;

import gr.cytech.sendium.core.message.StandardMessage;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

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
    void concatenationMayAppearBesideOtherInformationElements() {
        var first = messageWithUdh("0b050423f023f100037f0301");
        var second = messageWithUdh("0B050423F023F100037F0302");
        var parsed = MessageUtil.concatenation(first).orElseThrow();
        assertThat(parsed.referenceBits()).isEqualTo(8);
        assertThat(parsed.reference()).isEqualTo(127);
        assertThat(parsed.total()).isEqualTo(3);
        assertThat(parsed.ordinal()).isEqualTo(1);
        assertThat(parsed.normalizedUdh()).isEqualTo(MessageUtil.concatenation(second).orElseThrow().normalizedUdh());
        assertThat(MessageUtil.getNumberOfCurrentPart(first)).isEqualTo(1);
        assertThat(MessageUtil.getNumberOfTotalParts(first)).isEqualTo(3);
        var trailingPort = messageWithUdh("0B00037F0301050423F023F1");
        assertThat(MessageUtil.getNumberOfCurrentPart(trailingPort)).isEqualTo(1);
    }

    @Test
    void invalidConcatenationCannotBeAdmittedAsASupportedPart() {
        for (String udh : java.util.List.of("050003", "0500037F0001", "0500037F0200", "0500037F0203", "0500037F020Z",
                "0400037F0201", "0500047F0201", "0A00037F020100037F0201", "06080312340201")) {
            var message = messageWithUdh(udh);
            assertThat(MessageUtil.concatenation(message)).as(udh).isEmpty();
            assertThat(MessageUtil.is8BitMessagePart(message)).as(udh).isFalse();
            assertThat(MessageUtil.is16BitMessagePart(message)).as(udh).isFalse();
        }
    }

    @Test
    void zeroLengthAndTruncatedConcatenationElementsAreRejectedSafely() {
        for (int identifier : new int[]{0, 8}) {
            int requiredLength = identifier == 0 ? 3 : 4;
            for (int declared = 0; declared <= requiredLength; declared++) {
                for (int available = 0; available < requiredLength; available++) {
                    byte[] header = new byte[3 + available];
                    header[0] = (byte) (header.length - 1);
                    header[1] = (byte) identifier;
                    header[2] = (byte) declared;
                    java.util.Arrays.fill(header, 3, header.length, (byte) 1);
                    String udh = HexFormat.of().withUpperCase().formatHex(header);
                    StandardMessage message = messageWithUdh(udh);
                    assertThat(MessageUtil.concatenation(message)).as(udh).isEmpty();
                    assertThat(MessageUtil.is8BitMessagePart(message)).as(udh).isFalse();
                    assertThat(MessageUtil.is16BitMessagePart(message)).as(udh).isFalse();
                    assertThat(MessageUtil.getNumberOfCurrentPart(message)).as(udh).isZero();
                    assertThat(MessageUtil.getNumberOfTotalParts(message)).as(udh).isZero();
                }
            }
        }
    }

    @Test
    void maximumWidthReferencesRemainSupported() {
        var eightBit = MessageUtil.concatenation(messageWithUdh("050003FF0201")).orElseThrow();
        var sixteenBit = MessageUtil.concatenation(messageWithUdh("060804FFFF0201")).orElseThrow();
        assertThat(eightBit.referenceBits()).isEqualTo(8);
        assertThat(eightBit.reference()).isEqualTo(255);
        assertThat(sixteenBit.referenceBits()).isEqualTo(16);
        assertThat(sixteenBit.reference()).isEqualTo(65535);
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
