package ru.itmo.highload.common.domain.events;

import java.util.UUID;

/** Claim an event within the same transaction as the consumer's durable effect. */
public interface EventInbox {
    boolean claim(UUID eventId);
}
