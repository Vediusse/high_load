package ru.itmo.highload.catering.organization.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.organization.dto.in.UpdateDeliveryPointRequest;
import ru.itmo.highload.catering.organization.dto.out.DeliveryPointResponse;
import ru.itmo.highload.catering.organization.service.OrganizationService;
import ru.itmo.highload.common.config.StandardApiErrors;
import ru.itmo.highload.common.web.BlockingRequests;

@RestController
@RequestMapping("/api/v1/delivery-points")
@Tag(name = "Организации и точки выдачи")
@StandardApiErrors
@RequiredArgsConstructor
public class DeliveryPointController {

    private final BlockingRequests blocking;

    private final OrganizationService organizationService;

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('CLIENT_MANAGER')")
    @Operation(summary = "Изменить точку выдачи")
    public Mono<DeliveryPointResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDeliveryPointRequest request, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> organizationService.updateDeliveryPoint(id, request, representativeOrganizationId(actor)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CLIENT_MANAGER')")
    @Operation(summary = "Деактивировать точку выдачи")
    @ApiResponse(responseCode = "204", description = "Точка выдачи деактивирована")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deactivate(@PathVariable UUID id, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> {
            organizationService.deactivateDeliveryPoint(id, representativeOrganizationId(actor));
            return null;
        });
    }

    private UUID representativeOrganizationId(Jwt actor) {
        return actor.getClaimAsStringList("roles").contains("ORGANIZATION_REPRESENTATIVE")
                ? UUID.fromString(actor.getClaimAsString("organizationId"))
                : null;
    }
}
