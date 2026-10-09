package ru.itmo.highload.catering.identity.client;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import ru.itmo.highload.catering.identity.client.dto.out.OrganizationResponse;

@FeignClient(name = "order-service", url = "${clients.order.url:}")
public interface OrganizationClient {
    @GetMapping("/internal/v1/organizations/{id}")
    OrganizationResponse get(
            @PathVariable UUID id,
            @RequestHeader("Authorization") String bearer,
            @RequestHeader("X-Trace-Id") String trace);

}
