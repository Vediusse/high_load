package ru.itmo.highload.catering.catalog.domain;

public class CatalogFailure extends RuntimeException {
    public enum Kind { NOT_FOUND, CONFLICT, UNPROCESSABLE }
    private final Kind kind;
    private final String code;
    public CatalogFailure(Kind kind, String code, String message) {
        super(message); this.kind = kind; this.code = code;
    }
    public Kind kind() { return kind; }
    public String code() { return code; }
}
