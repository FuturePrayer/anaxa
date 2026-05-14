package cn.suhoan.anaxa.common.error;

/**
 * Signals that a requested AnaxaDB resource does not exist.
 */
public final class NotFoundException extends RuntimeException {
    /**
     * Creates an exception with a human-readable message.
     *
     * @param message error message
     */
    public NotFoundException(String message) {
        super(message);
    }
}
