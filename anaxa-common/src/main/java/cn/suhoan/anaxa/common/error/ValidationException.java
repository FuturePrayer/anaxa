package cn.suhoan.anaxa.common.error;

public final class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
