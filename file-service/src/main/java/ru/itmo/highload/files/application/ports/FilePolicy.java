package ru.itmo.highload.files.application.ports;

import java.time.Instant;
import ru.itmo.highload.files.domain.FileLimits;

public interface FilePolicy {
    FileLimits current();

    PolicyStatus status();

    record PolicyStatus(
            FileLimits limits, long generation, Instant appliedAt, String lastRejection) {}
}
