package ru.itmo.highload.catering.organization.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.organization.dto.in.CreateDeliveryPointRequest;
import ru.itmo.highload.catering.organization.dto.in.CreateOrganizationRequest;
import ru.itmo.highload.catering.organization.dto.in.UpdateOrganizationRequest;
import ru.itmo.highload.catering.organization.dto.out.DeliveryPointResponse;
import ru.itmo.highload.catering.organization.dto.out.OrganizationPageResult;
import ru.itmo.highload.catering.organization.dto.out.OrganizationResponse;
import ru.itmo.highload.catering.organization.service.OrganizationService;
import ru.itmo.highload.common.config.StandardApiErrors;
import ru.itmo.highload.common.dto.out.PageResponse;
import ru.itmo.highload.common.web.BlockingRequests;
import ru.itmo.highload.common.web.Pagination;

@RestController
@RequestMapping("/api/v1/organizations")
@Tag(name = "Организации и точки выдачи")
@StandardApiErrors
@RequiredArgsConstructor
public class OrganizationController {

    private final BlockingRequests blocking;

    private final OrganizationService organizationService;

    @PostMapping
    @PreAuthorize("hasRole('CLIENT_MANAGER')")
    @Operation(summary = "Создать организацию")
    @ApiResponse(responseCode = "201", description = "Организация создана")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<OrganizationResponse> create(
            @Valid @RequestBody CreateOrganizationRequest request, ServerHttpResponse httpResponse, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> {
            rejectRepresentativeCreate(actor);
            OrganizationResponse response = organizationService.createOrganization(request);
            httpResponse.getHeaders().setLocation(URI.create("/api/v1/organizations/" + response.id()));
            return response;
        });
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ORGANIZATION_REPRESENTATIVE', 'CLIENT_MANAGER')")
    @Operation(summary = "Получить организацию")
    public Mono<OrganizationResponse> get(@PathVariable UUID id, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> organizationService.getOrganization(id, representativeOrganizationId(actor)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('CLIENT_MANAGER')")
    @Operation(summary = "Изменить организацию")
    public Mono<OrganizationResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateOrganizationRequest request, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> organizationService.updateOrganization(id, request, representativeOrganizationId(actor)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CLIENT_MANAGER')")
    @Operation(summary = "Деактивировать организацию")
    @ApiResponse(responseCode = "204", description = "Организация деактивирована")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deactivate(@PathVariable UUID id, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> {
            organizationService.deactivateOrganization(id, representativeOrganizationId(actor));
            return null;
        });
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ORGANIZATION_REPRESENTATIVE', 'CLIENT_MANAGER')")
    @Operation(summary = "Получить страницу организаций")
    @ApiResponse(
            responseCode = "200",
            description = "Страница организаций",
            headers = @Header(name = "X-Total-Count", description = "Общее количество организаций"))
    public Mono<PageResponse<OrganizationResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, ServerHttpResponse httpResponse, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> {
            OrganizationPageResult result = organizationService.listOrganizations(
                    Pagination.pageRequest(page, size), representativeOrganizationId(actor));
            httpResponse.getHeaders().set("X-Total-Count", Long.toString(result.totalCount()));
            return result.body();
        });
    }

    @PostMapping("/{organizationId}/delivery-points")
    @PreAuthorize("hasRole('CLIENT_MANAGER')")
    @Operation(summary = "Добавить точку выдачи организации")
    @ApiResponse(responseCode = "201", description = "Точка выдачи создана")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<DeliveryPointResponse> createDeliveryPoint(
            @PathVariable UUID organizationId,
            @Valid @RequestBody CreateDeliveryPointRequest request, ServerHttpResponse httpResponse, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> {
            DeliveryPointResponse response = organizationService.createDeliveryPoint(
                    organizationId, request, representativeOrganizationId(actor));
            httpResponse.getHeaders().setLocation(URI.create("/api/v1/delivery-points/" + response.id()));
            return response;
        });
    }

    @GetMapping("/{organizationId}/delivery-points")
    @PreAuthorize("hasAnyRole('ORGANIZATION_REPRESENTATIVE', 'CLIENT_MANAGER')")
    @Operation(summary = "Получить страницу точек выдачи организации")
    public Mono<PageResponse<DeliveryPointResponse>> listDeliveryPoints(
            @PathVariable UUID organizationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> organizationService.listDeliveryPoints(
                    organizationId,
                    Pagination.pageRequest(page, size), representativeOrganizationId(actor)));
    }

    private UUID representativeOrganizationId(Jwt actor) {
        return actor.getClaimAsStringList("roles").contains("ORGANIZATION_REPRESENTATIVE")
                ? UUID.fromString(actor.getClaimAsString("organizationId"))
                : null;
    }

    private void rejectRepresentativeCreate(Jwt actor) {
        if (representativeOrganizationId(actor) != null) {
            throw new ru.itmo.highload.common.error.ApiException(
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Представитель не может создавать другую организацию");
        }
    }
}
