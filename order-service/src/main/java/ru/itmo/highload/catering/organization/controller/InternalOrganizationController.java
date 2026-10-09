package ru.itmo.highload.catering.organization.controller;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.organization.dto.out.OrganizationLookupResponse;
import ru.itmo.highload.catering.organization.service.OrganizationService;
import ru.itmo.highload.common.error.ApiException;
import ru.itmo.highload.common.web.BlockingRequests;

@Hidden
@RestController
@RequestMapping("/internal/v1/organizations")
@RequiredArgsConstructor
public class InternalOrganizationController {
    private final OrganizationService organizations;
    private final BlockingRequests blocking;

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public Mono<OrganizationLookupResponse> get(@PathVariable UUID id, @AuthenticationPrincipal Jwt actor) {
        if (actor.getClaimAsStringList("roles").contains("ORGANIZATION_REPRESENTATIVE")
                && !id.toString().equals(actor.getClaimAsString("organizationId"))) {
            return Mono.error(new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Организация не найдена"));
        }
        return blocking.call(() -> {
            var organization = organizations.getOrganization(id);
            return new OrganizationLookupResponse(organization.id(), organization.active());
        });
    }
}
