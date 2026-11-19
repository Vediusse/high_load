package ru.itmo.highload.notification.adapters.out.identity;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import ru.itmo.highload.common.dto.out.PageResponse;

@FeignClient(name = "identity-service", url = "${identity.service-url:}")
public interface IdentityClient {
    @GetMapping("/internal/v1/recipients")
    PageResponse<UUID> recipients(
            @RequestParam String role,
            @RequestParam(required = false) UUID organizationId,
            @RequestParam int page,
            @RequestParam int size,
            @RequestHeader("X-Notification-Credential") String credential);
}
