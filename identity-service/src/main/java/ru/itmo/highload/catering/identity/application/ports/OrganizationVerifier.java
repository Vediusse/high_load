package ru.itmo.highload.catering.identity.application.ports;

import java.util.UUID;

public interface OrganizationVerifier {
    void requireActive(UUID id, String bearer);
}
