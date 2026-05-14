package gr.cytech.sendium.core.smpp.server;

public class VendorSpecificConstants {

    //Provider specific codes
    /**
     * Insufficient credits to send message
     */
    public static final int STATUS_RINVBALANCE = 0x00000401;
    /**
     * Number unroutable. Do not retry
     */
    public static final int STATUS_ROUTERR = 0x00000402;
    /**
     * Account Frozen
     */
    public static final int STATUS_ACCFROZ = 0x00000403;
    /**
     * Bad Data
     */
    public static final int STATUS_DATAERR = 0x00000404;
    /**
     * Number blacklisted in system
     */
    public static final int STATUS_NUMBLACKLIST = 0x00000405;
    /**
     * Client blacklisted in system
     */
    public static final int STATUS_CLIENTBLACKLIST = 0x00000406;
    /**
     * Temporary System failure, please retry.
     */
    public static final int STATUS_TEMPSYSERR = 0x00000407;
    /**
     * Number unroutable. Do not retry.
     */
    public static final int STATUS_NUMNOROUTABLE = 0x00000408;
    /**
     * Number Temporarily unroutable, please try again.
     */
    public static final int STATUS_NUMTEMPNOROUTABLE = 0x00000409;

    /**
     * ESME prohibited from using specified operation
     */
    public static final int STATUS_RPROHIBITED = 0x0000040A;
    /**
     * Invalid data coding scheme
     */
    public static final int STATUS_RINVDCS = 0x0000040B;
    /**
     * Message text is null
     */
    public static final int STATUS_NOMSGBODY = 0x0000040C;
    /**
     * Maximum Connections per IP
     */
    public static final int STATUS_MAXCONSPERIP = 0x0000040D;
    public static final int STATUS_MAXCONSPERUSER = 0x0000040E;

    /**
     * Number is blocked. Do not retry
     */
    public static final int STATUS_NUMBLOCKED = 0x00000410;
    /**
     * Billing Reference Error. Do not retry
     */
    public static final int STATUS_BILLINGERR = 0x00000411;
    /**
     * The text content of this message is prohibited on this product.
     */
    public static final int STATUS_TEXTPROHIBIT = 0x00000412;
    /**
     * The number portability operator lookup failed.
     */
    public static final int STATUS_HLRERR = 0x00000413;
    /**
     * Invalid ContentType. Do not retry.
     */
    public static final int STATUS_INVCONTENTTYPE = 0x00000414;
    /**
     * Originator missing from the message. Do not retry
     */
    public static final int STATUS_NOORIGINATOR = 0x00000415;
    /**
     * End user deactivated. Do not retry
     */
    public static final int STATUS_USERDEACTIVATED = 0x00000416;
    /**
     * End user suspended. Do not retry
     */
    public static final int STATUS_USERSUSPENDED = 0x00000417;

    public static final short TAG_SOURCE_NETWORK_ID = 0x1400;
    public static final String TAG_NAME_SOURCE_NETWORK_ID = "source_network_id";
}
