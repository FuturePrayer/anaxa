package cn.suhoan.anaxa.common.error;

/**
 * Signals that an AnaxaDB request failed validation.
 */
public final class ValidationException extends RuntimeException {
    /**
     * Creates an exception with a human-readable message.
     *
     * @param message error message
     */
    public ValidationException(String message) {
        super(message);
    }
}
