package gr.cytech.sendium.external.filter;

/**
 * This enumeration defines all the different status codes that a filter
 * can return. The router handle each of these status codes in the appropriate
 * way.
 * <br />
 * Status Codes:
 * UNCHANGED: The filter did not change the message
 * MODIFIED: The filter modified the Message. The router will continue the processing.
 * DROP: The router will drop the message immediately.
 * REENQUEUE: The router will re-enqueue the message. All the routing rules will be applied again
 * RESCHEDULE: The router will re-schedule the message. NOT IMPLEMENTED. Fallback to REENQUEUE.
 */
public enum FilterStatusCodes {
    UNKNOWN(FilterStatusCodes.STATUS_CODE_UNKNOWN),
    UNCHANGED(FilterStatusCodes.STATUS_CODE_UNCHANGED),
    MODIFIED(FilterStatusCodes.STATUS_CODE_MODIFIED),
    DROP(FilterStatusCodes.STATUS_CODE_DROP),
    REENQUEUE(FilterStatusCodes.STATUS_CODE_REENQUEUE),
    RESCHEDULE(FilterStatusCodes.STATUS_CODE_RESCHEDULE),
    RETRY(FilterStatusCodes.STATUS_CODE_RETRY);

    public static final int STATUS_CODE_UNKNOWN = -1;
    public static final int STATUS_CODE_UNCHANGED = 0;
    public static final int STATUS_CODE_MODIFIED = 1;
    public static final int STATUS_CODE_DROP = 2;
    public static final int STATUS_CODE_REENQUEUE = 3;
    public static final int STATUS_CODE_RESCHEDULE = 4;
    public static final int STATUS_CODE_RETRY = 5;

    private final int value;

    private FilterStatusCodes(int value) {
        this.value = value;
    }

    public static FilterStatusCodes valueOf(int value) {
        for (FilterStatusCodes val : values()) {
            if (val.value == value) {
                return val;
            }
        }
        return UNKNOWN;
    }

    public int value() {
        return value;
    }
}
