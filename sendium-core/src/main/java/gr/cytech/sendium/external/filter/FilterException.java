package gr.cytech.sendium.external.filter;

import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.core.message.StandardMessage;

import java.io.IOException;

/**
 * This exception must be thrown by all the filters. It contains useful information that
 * informs the router about the action it has to take.
 */
public class FilterException extends IOException {

    private final FilterStatusCodes statusCode;
    private final AbstractOutWorker<?> filter;
    private final StandardMessage message;

    /**
     * The constructor of the exception.
     *
     * @param filter     The filter that throws the exception
     * @param statusCode The status code informs the router about the policy must be applied.
     * @param messageObj The message object upon which the filter was applied
     * @param message    The exception message.
     */
    public FilterException(AbstractOutWorker<?> filter, FilterStatusCodes statusCode, StandardMessage messageObj, String message) {
        super(message);
        this.filter = filter;
        this.statusCode = statusCode;
        this.message = messageObj;
    }

    public FilterStatusCodes getStatusCode() {
        return (statusCode != null) ? statusCode : FilterStatusCodes.UNKNOWN;
    }

    /**
     * For performance reasons we just prevent the stack trace
     * to be filled in.
     */
    public Throwable fillInStackTrace() {
        return this;
    }

    public AbstractOutWorker<?> getFilter() {
        return filter;
    }

    public StandardMessage getMessageObj() {
        return message;
    }

}
