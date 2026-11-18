package ru.itmo.highload.common.domain.events;
/** Persist an event in the caller's business transaction; never contacts the broker. */
public interface EventOutbox { void append(EventEnvelope event); }
