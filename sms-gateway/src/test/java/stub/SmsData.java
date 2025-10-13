package stub;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.google.common.base.Joiner;

public class SmsData {
    public static final String ENCODING_GSM7 = CharsetUtil.NAME_GSM;
    public static final String ENCODING_LATIN1 = CharsetUtil.NAME_ISO_8859_1;
    public static final String ENCODING_UCS2 = CharsetUtil.NAME_UCS_2;
    public static final String ENCODING_BINARY = CharsetUtil.NAME_UTF_8;
    public static final String FROM = "ci";
    public static final String TO = "306900000000";
    public static final long TOl = Long.parseLong(TO);
    public static final String SHORT_CODE = "12345";

    public static final String LATIN1_BODY_SHORT =
            "!#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~¡¢£¤¥¦";

    public static final String GSM7_BODY_SHORT =
            "@£$¥èéùìòØøÅå Δ_ΦΓΛΩΠΨΣΘΞÆæßÉ !#¤%&\\'()*+,-./ :;<=>? ¡ABCDEFGHIJKLMNO PQRSTUVWXYZÄÖÑÜ§ ¿abcdefghijklmno pqrstuvwxyzäöñüà ^{}[~]| €";

    public static final String[] GSM7_UDH_LONG_PARTS =
            new String[] {"050003030201", "050003030202"};

    public static final String[] GSM7_BODY_LONG_PARTS =
            new String[] {
                "@£$¥èéùìòØøÅå Δ_ΦΓΛΩΠΨΣΘΞÆæßÉ !#¤%&\\'()*+,-./ :;<=>? ¡ABCDEFGHIJKLMNO PQRSTUVWXYZÄÖÑÜ§ ¿abcdefghijklmno pqrstuvwxyzäöñüà ^{}[~]| €@£$¥èéùìòØ@£$¥",
                "ΦΓΛΩΠΨΣΘΞ"
            };
    public static final String GSM7_BODY_LONG = Joiner.on("").join(SmsData.GSM7_BODY_LONG_PARTS);

    public static final String[] LATIN1_UDH_LONG_PARTS =
            new String[] {"050003030201", "050003030202"};

    public static final String[] LATIN1_BODY_LONG_PARTS =
            new String[] {
                "!#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~¡¢£¤¥¦§¨©ª«¬\u00AD®¯°±²³´µ¶·¸¹º»¼½¾¿ÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏÐÑÒÓÔ",
                "ÕÖ×ØÙÚÛÜÝÞßàáâãäåæçèéêëìíîïðñòóôõö÷øùúûüýþÿ\""
            };

    public static final String UCS2_BODY_SHORT = "Αυτό είναι ένα δοκιμαστικό μύνημα";

    public static final String[] UCS2_UDH_LONG_PARTS =
            new String[] {"0608047D140201", "0608047D140202"};

    public static final String[] UCS2_8BIT_UDH_LONG_PARTS =
            new String[] {"050003030201", "050003030202"};

    public static final String[] UCS2_BODY_LONG_PARTS =
            new String[] {
                "Αυτό είναι ένα πολύ μεγάλο μήνυμα που θα πρέπει να σταλεί ως δύο μέ",
                "ρη.Δεύτερο μέρος."
            };

    public static final String UCS2_BODY_LONG = Joiner.on("").join(UCS2_BODY_LONG_PARTS);

    public static final String WAP_UDH = "0605040B8423F0";
    public static final String WAP_BODY =
            "25060403AE81EA01056A0045C60C03752E736D73702E67722F643565676E6200080103436F6D6520696E746F206F75722073746F726520746F20676574206120667265652070697A7A61212121000101";

    public static final String[] BINARY_UDH_LONG_PARTS =
            new String[] {"050003030301", "050003030302", "050003030303"};

    public static final String[] BINARY_BODY_LONG_PARTS =
            new String[] {
                "546869732069732061206c6f6e6720636f6e636174656e617465642074657874206d6573736167652074686174206d757374206265207265636569766564206173206d756c7469706c652070617274732066726f6d2074686520736d70706170692e2054686573652070617274732077696c6c2062652073746f72656420696e207468652064",
                "436f6e73657175656e746c792c20746865206d6f62696c652070726f76696465722073686f756c642072657475726e206120646966666572656e742064656c6976657279207265706f72747320666f7220656163682073696e676c652070617274732e",
                "6174616261736520616c6f6e672077697468207468652072656c6174697665207573657220646174612068656164657220696e666f726d6174696f6e20616e642077696c6c2062652073656e7420696e746f20746865206d6f62696c652070726f7669646572732073657061726174656c79206173206d756c7469706c652070617274732e20"
            };

    public static final String BINARY_BODY_LONG = Joiner.on("").join(BINARY_BODY_LONG_PARTS);

    public static final String[] UCS2_UDH_TOO_LONG_PARTS =
            new String[] {
                "0608047D140A01",
                "0608047D140A02",
                "0608047D140A03",
                "0608047D140A04",
                "0608047D140A05",
                "0608047D140A06",
                "0608047D140A07",
                "0608047D140A08",
                "0608047D140A09",
                "0608047D140A0A"
            };

    public static final String[] UCS2_BODY_TOO_LONG_PARTS =
            new String[] {
                "Αυτό είναι ένα πολύ μεγάλο μήνυμα που θα πρέπει να σταλεί ως πολλάά",
                "Αυτό είναι ένα πολύ μεγάλο μήνυμα που θα πρέπει να σταλεί ως πολλάά",
                "Αυτό είναι ένα πολύ μεγάλο μήνυμα που θα πρέπει να σταλεί ως πολλάά",
                "Αυτό είναι ένα πολύ μεγάλο μήνυμα που θα πρέπει να σταλεί ως πολλάά",
                "Αυτό είναι ένα πολύ μεγάλο μήνυμα που θα πρέπει να σταλεί ως πολλάά",
                "Αυτό είναι ένα πολύ μεγάλο μήνυμα που θα πρέπει να σταλεί ως πολλάά",
                "Αυτό είναι ένα πολύ μεγάλο μήνυμα που θα πρέπει να σταλεί ως πολλάά",
                "Αυτό είναι ένα πολύ μεγάλο μήνυμα που θα πρέπει να σταλεί ως πολλάά",
                "Αυτό είναι ένα πολύ μεγάλο μήνυμα που θα πρέπει να σταλεί ως πολλάά",
                "Αυτό είναι ένα πολύ μεγάλο μήνυμα που θα πρέπει να σταλεί ως πολλάά"
            };

    public static final String UCS2_BODY_TOO_LONG = Joiner.on("").join(UCS2_BODY_TOO_LONG_PARTS);
}
