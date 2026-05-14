package gr.cytech.sendium.util;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import gr.cytech.sendium.core.message.StandardMessage;
import java.util.Set;

public class MessageUtil {

    public static final Set<Character> OneByteCharacters = Set.of(
            '@', '£', '$', '¥', 'è', 'é', 'ù', 'ì', 'ò', 'ç',
            '\n', 'Ø', 'ø', '\r', 'Å', 'å', '\u0394', '_', // 'Δ'
            '\u03a6', '\u0393', '\u039b', '\u03a9', '\u03a0', // 'Φ', 'Γ', 'Λ', 'Ω', 'Π'
            '\u03a8', '\u03a3', '\u0398', '\u039e', 'Æ', // 'Ψ', 'Σ', 'Θ', 'Ξ'
            'æ', 'ß', 'É', ' ', '!', '"', '#', '¤', '%', '&',
            '\'', '(', ')', '*', '+', ',', '-', '.', '/', '0',
            '1', '2', '3', '4', '5', '6', '7', '8', '9', ':',
            ';', '<', '=', '>', '?', '¡', 'A', 'B', 'C', 'D',
            'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N',
            'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X',
            'Y', 'Z', 'Ä', 'Ö', 'Ñ', 'Ü', '§', '¿', 'a', 'b',
            'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l',
            'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v',
            'w', 'x', 'y', 'z', 'ä', 'ö', 'ñ', 'ü', 'à'
    );

    public static final Set<Character> TwoByteCharacters = Set.of('{', '}', '[', ']', '^', '~', '|', '€', '\\');

    /**
     * The the count of the smses needed to be send for the message, if udh exists and message body is longer than
     * 140bits the message body is trimmed appropriately to only be 1 sms(depending on the message type)
     */
    public static int getSmsCntAndTrimForUdh(StandardMessage msg, boolean ccat) {
        if (msg.binheader == null || msg.binheader.isEmpty()) {
            return getSmsCnt(msg.body, msg.type, ccat);
        }
        //if udh is not null, we assume that the message is 1 only
        //so we trim the body if it more than the allowed

        //udh is hex encoded, thus 2 chars for each byte
        //multiplying by 4, in order to calculate bits (not bytes, so as to not lose precision using int)
        int udhSize = msg.binheader.length() * 4;
        //max bits (140 bytes is the max load of an sms, as the standard goes)
        int maxAvailableBodyBits = 140 * 8 - udhSize;
        switch (msg.type) {
            case StandardMessage.MSG_TEXT:
                //length in bits
                int charsCount = StandardMessage.getCharsCnt(msg.body, msg.type);
                int textLng = charsCount * 7;
                if (textLng > maxAvailableBodyBits) {
                    int bitsToRemove = textLng - maxAvailableBodyBits;
                    int removeIndex = msg.body.length();
                    int bitsRemoved = 0;
                    while (bitsToRemove - bitsRemoved > 0) {
                        removeIndex--;
                        //remove last characters from body, until it becomes less than the max allowed
                        char removedChar = msg.body.charAt(removeIndex);
                        bitsRemoved += (StandardMessage.isCharDoubleInGsm(removedChar) ? 2 : 1) * 7;
                    }
                    msg.body = msg.body.substring(0, removeIndex);
                }
                break;
            case StandardMessage.MSG_UCS2:
                //length in bits
                int charsCounter = StandardMessage.getCharsCnt(msg.body, msg.type);
                //each ucs2 char is 2 bytes, so in bits is it 2*8
                int ucs2Lng = charsCounter * 16;
                if (ucs2Lng > maxAvailableBodyBits) {
                    int charsToRemove = (ucs2Lng - maxAvailableBodyBits) / 16; //chars to remove
                    int removeIndex = msg.body.length() - charsToRemove;
                    msg.body = msg.body.substring(0, removeIndex);
                }
                break;
            case StandardMessage.MSG_BINARY:
                int binLng = msg.body.length() * 4; //we assume body is hex string encoded, so 2 chars for each byte
                if (binLng > maxAvailableBodyBits) {
                    msg.body = msg.body.substring(0, maxAvailableBodyBits / 4);
                }
                break;
            default:
                return 1;
        }

        return 1;
    }

    /**
     * Checks if the provided message contains a valid UDH that represents a part of
     * an 8-bit concatenated message. That means that the UDH must start with 050003.
     *
     * @param message The message to be checked
     * @return True if the provided message has a supported UDH format
     */
    public static boolean is8BitMessagePart(StandardMessage message) {
        return hasUdh(message) && message.binheader.startsWith(UdhPrefix.BIT_8.value);
    }

    /**
     * Checks if the provided message contains a valid UDH that represents a part of
     * an 16-bit concatenated message. That means that the UDH must start with 060804.
     *
     * @param message The message to be checked
     * @return True if the provided message has a supported UDH format
     */
    public static boolean is16BitMessagePart(StandardMessage message) {
        return hasUdh(message) && message.binheader.startsWith(UdhPrefix.BIT_16.value);
    }

    public static boolean hasUdh(StandardMessage message) {
        return !Strings.isNullOrEmpty(message.binheader);
    }

    public static String getMessageReference(StandardMessage message) {
        String msgRefNum = null;
        if (is8BitMessagePart(message)) {
            // The digits 6-8 contain the reference number of the 8 bit concatenated message.
            msgRefNum = message.binheader.substring(6, 8);
        } else if (is16BitMessagePart(message)) {
            // The digits 6-10 contain the reference number of the 16 bit concatenated message.
            msgRefNum = message.binheader.substring(6, 10);
        }

        //The ref can be the same across multiple clients/apis. By prepending the user_id
        //we guaranty that if a client uses only one API (e.g smpp or http) the msgRefNum
        //will be unique. However, if the same client uses multiple APIs there will be still
        //a small possibility to have an overlapping reference.
        if (msgRefNum != null) {
            msgRefNum = message.owner_id.concat(":").concat(String.valueOf(msgRefNum));
        }
        return msgRefNum;
    }

    public static int getNumberOfCurrentPart(StandardMessage message) {
        if (is8BitMessagePart(message) || is16BitMessagePart(message)) {
            int length = message.binheader.length();
            return Integer.parseInt(message.binheader.substring(length - 2, length), 16);
        }
        return 0;
    }

    public static int getNumberOfTotalParts(StandardMessage message) {
        if (is8BitMessagePart(message)) {
            return Integer.parseInt(message.binheader.substring(8, 10), 16);
        } else if (is16BitMessagePart(message)) {
            return Integer.parseInt(message.binheader.substring(10, 12), 16);
        }
        return 0;
    }

    public static String validateText(String udh, String msgBody, int msgType, int maxBodyLength) {
        // validate SMS body
        if (msgType == StandardMessage.MSG_TEXT) {
            msgBody = unicodeGreekToGSM(msgBody);
        }

        // validate SMS length
        return validateSmsLength(udh, msgBody, msgType, maxBodyLength);
    }

    public static String validateSmsLength(String udh, String msgBody, int msgType, int maxBodyLength) {
        int msgBodyLength = msgBody.length();
        int udhLength = 0;
        if (udh != null) {
            udhLength = udh.length();
        }

        if (
                msgType == StandardMessage.MSG_UCS2 &&
                        msgBodyLength > maxBodyLength
        ) {
            msgBody = msgBody.substring(0, maxBodyLength);
        } else if (
                (msgType == StandardMessage.MSG_PUSH || msgType == StandardMessage.MSG_BINARY) &&
                        (msgBodyLength + udhLength) / 2 > maxBodyLength
        ) {
            msgBody = msgBody.substring(0, maxBodyLength - udhLength);
        } else if (msgType == StandardMessage.MSG_TEXT) {
            int[] counters = gsmCharCounter(msgBody, true, maxBodyLength);
            if (msgBodyLength > counters[1]) {
                msgBody = msgBody.substring(0, counters[1]);
            }
        }
        return msgBody;
    }

    /**
     * Count the characters included in the specified text, until the maxLength is reached.
     *
     * <p>If chopExtra is true, stop there and return both the counter of
     * GSM characters and that of string characters.</p>
     *
     * <p>If chopExtra is false, do not stop when maxLength is reached.
     * Count and return both the counter of GSM characters and that of string characters.</p>
     */
    public static int[] gsmCharCounter(String text, boolean chopExtra, int maxLength) {
        if (text == null) {
            return null;
        }

        int count = 0;
        int charCount = 0;
        char[] chars = text.toCharArray();
        for (char ch : chars) {
            count++;    //add one to the count
            charCount++;
            boolean escapedChar = TwoByteCharacters.contains(ch);
            if (escapedChar) {
                count++;
            }
            if (chopExtra && count > maxLength) { //loop exit condition, if we want to chop text at 160
                return new int[]{(escapedChar) ? count - 2 : count - 1, charCount - 1};
            }
        }
        return new int[]{count, charCount};
    }

    public static int getSmsCnt(String body, int smtype, boolean ccat) {
        if (body == null) {
            return 0;
        }

        int bodyLng = StandardMessage.getCharsCnt(body, smtype);

        switch (smtype) {
            case StandardMessage.MSG_PUSH:
                if (bodyLng <= 133) {
                    return 1;
                } else if ((bodyLng > 133) && ccat) {
                    return (bodyLng + 127) / 128;
                } else {
                    return -1; // this is just an error code
                }
            case StandardMessage.MSG_UCS2:
                if (bodyLng <= 70) {
                    return 1; // + 1;// +1 because it is VAS
                } else if ((bodyLng > 70) && ccat) {
                    return ((bodyLng + 66) / 67); // + 1;// +1 because it is VAS
                }
                return ((bodyLng + 69) / 70); // * 2;
            case StandardMessage.MSG_PING:
            case StandardMessage.MSG_FLASH:
                // we only send those kinds up to 160 chars/sms (no concatenation)
                return 1;
            case StandardMessage.MSG_VIBER:
                return (bodyLng + 999) / 1_000;
            default:
            case StandardMessage.MSG_TEXT:
                if (bodyLng <= 160) {
                    return 1;
                } else if ((bodyLng > 160) && ccat) {
                    return (bodyLng + 152) / 153;
                }
                return (bodyLng + 159) / 160;
        }
    }

    /**
     * Replaces all non-gsm characters to corresponding gsm characters
     *
     * @param text The text to be transformed. If null or empty, an empty string is returned.
     * @return text after transformation
    */
    public static String unicodeGreekToGSM(String text) {
        if (text == null || text.length() == 0) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isValidGsmChar(c)) {
                result.append(c);
            } else {
                int charCode = text.codePointAt(i);
                if (charCode == 913 || charCode == 902 || charCode == 940 || charCode == 945) {
                    result.append("Α");
                } else if (charCode == 914 || charCode == 946) {
                    result.append("Β");
                } else if (charCode == 915 || charCode == 947) {
                    result.append("Γ");
                } else if (charCode == 916 || charCode == 948) {
                    result.append("Δ");
                } else if (charCode == 904 || charCode == 917 || charCode == 941 || charCode == 949) {
                    result.append("Ε");
                } else if (charCode == 986) {
                    result.append("S");
                } else if (charCode == 988) {
                    result.append("F");
                } else if (charCode == 918 || charCode == 950) {
                    result.append("Z");
                } else if (charCode == 919 || charCode == 942 || charCode == 905 || charCode == 951) {
                    result.append("H");
                } else if (charCode == 920 || charCode == 952) {
                    result.append("Θ");
                } else if (charCode == 921 ||
                        charCode == 953 ||
                        charCode == 906 ||
                        charCode == 938 ||
                        charCode == 943 ||
                        charCode == 970 ||
                        charCode == 912
                ) {
                    result.append("I");
                } else if (charCode == 922 || charCode == 954) {
                    result.append("K");
                } else if (charCode == 923 || charCode == 955) {
                    result.append("Λ");
                } else if (charCode == 924 || charCode == 956) {
                    result.append("M");
                } else if (charCode == 925 || charCode == 957) {
                    result.append("N");
                } else if (charCode == 926 || charCode == 958) {
                    result.append("Ξ");
                } else if (charCode == 927 || charCode == 959 || charCode == 908 || charCode == 972) {
                    result.append("O");
                } else if (charCode == 928 || charCode == 960) {
                    result.append("Π");
                } else if (charCode == 929 || charCode == 961) {
                    result.append("P");
                } else if (charCode == 931 || charCode == 962 || charCode == 963) {
                    result.append("Σ");
                } else if (charCode == 932 || charCode == 964) {
                    result.append("T");
                } else if (charCode == 933 ||
                        charCode == 944 ||
                        charCode == 910 ||
                        charCode == 973 ||
                        charCode == 939 ||
                        charCode == 965 ||
                        charCode == 971
                ) {
                    result.append("Y");
                } else if (charCode == 934 || charCode == 966) {
                    result.append("Φ");
                } else if (charCode == 935 || charCode == 967) {
                    result.append("X");
                } else if (charCode == 936 || charCode == 968) {
                    result.append("Ψ");
                } else if (charCode == 937 || charCode == 969 || charCode == 974 || charCode == 911) {
                    result.append("Ω");
                }
            }
        }

        return result.toString();
    }

    private static boolean isValidGsmChar(char ch) {
        return OneByteCharacters.contains(ch) || TwoByteCharacters.contains(ch);
    }

    public enum UdhPrefix {
        BIT_8("050003"), BIT_16("060804");

        public final String value;

        UdhPrefix(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("value", value).toString();
        }
    }
}
