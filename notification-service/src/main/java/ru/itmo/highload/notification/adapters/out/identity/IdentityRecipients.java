package ru.itmo.highload.notification.adapters.out.identity;

import java.util.UUID;
import ru.itmo.highload.common.dto.out.PageResponse;
import ru.itmo.highload.notification.application.ports.RecipientDirectory;

public final class IdentityRecipients implements RecipientDirectory {
    private final IdentityClient client;
    private final String credential;

    public IdentityRecipients(IdentityClient client, String credential) {
        if (credential.length() < 32)
            throw new IllegalArgumentException("Configure notification Identity credential");
        this.client = client;
        this.credential = credential;
    }

    public PageResponse<UUID> page(String role, UUID organizationId, int page) {
        var result = client.recipients(role, organizationId, page, 50, credential);
        if (result == null
                || result.page() != page
                || result.size() != 50
                || result.items() == null
                || result.items().size() > 50
                || result.items().stream().anyMatch(java.util.Objects::isNull)
                || (result.hasNext() && result.items().isEmpty()))
            throw new IllegalStateException("Invalid Identity recipient page");
        return result;
    }
}
