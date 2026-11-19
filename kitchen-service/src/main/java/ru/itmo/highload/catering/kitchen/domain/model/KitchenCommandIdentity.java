package ru.itmo.highload.catering.kitchen.domain.model;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class KitchenCommandIdentity {
    private KitchenCommandIdentity() {}

    public static UUID forAction(UUID orderId, KitchenAction action, long expectedVersion) {
        return UUID.nameUUIDFromBytes(
                ("production:v1:" + orderId + ":" + action + ":" + expectedVersion)
                        .getBytes(StandardCharsets.UTF_8));
    }
}
