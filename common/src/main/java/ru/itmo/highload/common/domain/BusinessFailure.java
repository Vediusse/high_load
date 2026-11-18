package ru.itmo.highload.common.domain;

/** A use-case failure; transport status mapping belongs to an inbound adapter. */
public class BusinessFailure extends RuntimeException {
    public enum Kind { VALIDATION, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, CONFLICT, REJECTED, UNAVAILABLE }
    private final Kind kind;
    private final String code;
    public BusinessFailure(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }
    public Kind kind() { return kind; }
    public String code() { return code; }
}
