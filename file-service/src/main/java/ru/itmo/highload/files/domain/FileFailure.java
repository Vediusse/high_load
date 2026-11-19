package ru.itmo.highload.files.domain;

public final class FileFailure extends RuntimeException {
    public enum Kind {
        TOO_LARGE,
        UNSUPPORTED_TYPE,
        CONTENT_MISMATCH,
        INTEGRITY,
        STORAGE
    }

    private final Kind kind;
    private final String code;

    public FileFailure(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
