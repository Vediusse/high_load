package ru.itmo.highload.catering.order.application;

import java.util.UUID;

/** Immutable request facts needed by the blocking order use cases. */
public record OrderAccess(
        UUID userId,
        boolean clientManager,
        boolean organizationRepresentative,
        UUID organizationId) {

    public boolean restrictsToOrganization() {
        return organizationRepresentative;
    }

    public boolean hidesDraftOrders() {
        return !clientManager && !organizationRepresentative;
    }
}
