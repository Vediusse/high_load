package ru.itmo.highload.catering.catalog.domain;

public class DataConflict extends RuntimeException {
    public DataConflict(Throwable cause) { super("Data constraint violation", cause); }
}
