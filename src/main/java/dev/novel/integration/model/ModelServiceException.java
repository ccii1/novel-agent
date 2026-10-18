package dev.novel.integration.model;

public final class ModelServiceException extends RuntimeException {
    public enum Reason { CONNECT, TIMEOUT, AUTH, UPSTREAM, INVALID_RESPONSE, INTERRUPTED }
    private final String provider;
    private final Reason reason;
    public ModelServiceException(String provider,Reason reason,String message,Throwable cause) {
        super(message,cause);this.provider=provider;this.reason=reason;
    }
    public String provider() { return provider; }
    public Reason reason() { return reason; }
}
