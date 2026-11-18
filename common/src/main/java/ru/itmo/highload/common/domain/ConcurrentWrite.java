package ru.itmo.highload.common.domain;

public class ConcurrentWrite extends RuntimeException {
    public ConcurrentWrite(Throwable cause) {
        super("Concurrent aggregate update", cause);
    }
}
