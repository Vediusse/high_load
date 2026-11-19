package ru.itmo.highload.files.application.ports;

import java.util.UUID;

public interface BinaryStorage {
    void write(UUID key, byte[] bytes);

    byte[] read(UUID key);

    void removeUncommitted(UUID key);
}
