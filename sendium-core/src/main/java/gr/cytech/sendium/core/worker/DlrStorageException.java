package gr.cytech.sendium.core.worker;

public class DlrStorageException extends RuntimeException {
    public DlrStorageException(String message) {
        super(message);
    }

    public DlrStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
