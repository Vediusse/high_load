package ru.itmo.highload.common.domain.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Versioned public event contract. No transport credentials or personal contact data. */
public record EventEnvelope(
        UUID eventId,
        EventType eventType,
        UUID aggregateId,
        Instant occurredAt,
        int version,
        Payload payload) {
    public EventEnvelope {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(eventType);
        Objects.requireNonNull(aggregateId);
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(payload);
        if (version != 1) throw new IllegalArgumentException("Unsupported event schema");
        if ((eventType == EventType.FILE_UPLOADED || eventType == EventType.FILE_DELETED)
                && (payload.fileId() == null
                        || payload.relationType() == null
                        || payload.fileVersion() == null))
            throw new IllegalArgumentException("File event metadata required");
    }

    public record Payload(
            UUID organizationId,
            long orderVersion,
            UUID actorUserId,
            UUID fileId,
            String relationType,
            Long fileVersion) {
        public Payload {
            Objects.requireNonNull(organizationId);
            if (orderVersion < 0 || (fileVersion != null && fileVersion < 0))
                throw new IllegalArgumentException("Invalid aggregate version");
        }

        public static Payload order(UUID organizationId, long version, UUID actor) {
            return new Payload(organizationId, version, actor, null, null, null);
        }
    }
}
