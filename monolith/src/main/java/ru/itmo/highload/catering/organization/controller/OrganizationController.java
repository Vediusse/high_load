package ru.itmo.highload.catering.organization.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.itmo.highload.catering.common.dto.PageResponse;
import ru.itmo.highload.catering.common.config.StandardApiErrors;
import ru.itmo.highload.catering.common.web.Pagination;
import ru.itmo.highload.catering.organization.dto.CreateDeliveryPointRequest;
import ru.itmo.highload.catering.organization.dto.CreateOrganizationRequest;
import ru.itmo.highload.catering.organization.dto.DeliveryPointResponse;
import ru.itmo.highload.catering.organization.dto.OrganizationPageResult;
import ru.itmo.highload.catering.organization.dto.OrganizationResponse;
import ru.itmo.highload.catering.organization.dto.UpdateOrganizationRequest;
import ru.itmo.highload.catering.organization.service.OrganizationService;

@RestController
@RequestMapping("/api/v1/organizations")
@Tag(name = "Организации и точки выдачи")
@StandardApiErrors
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService organizationService;

    @PostMapping
    @Operation(summary = "Создать организацию")
    @ApiResponse(responseCode = "201", description = "Организация создана")
    public ResponseEntity<OrganizationResponse> create(@Valid @RequestBody CreateOrganizationRequest request) {
        OrganizationResponse response = organizationService.createOrganization(request);
        return ResponseEntity.created(URI.create("/api/v1/organizations/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Получить организацию")
    public ResponseEntity<OrganizationResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(organizationService.getOrganization(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Изменить организацию")
    public ResponseEntity<OrganizationResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateOrganizationRequest request) {
        return ResponseEntity.ok(organizationService.updateOrganization(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Деактивировать организацию")
    @ApiResponse(responseCode = "204", description = "Организация деактивирована")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        organizationService.deactivateOrganization(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @Operation(summary = "Получить страницу организаций")
    @ApiResponse(
            responseCode = "200",
            description = "Страница организаций",
            headers = @Header(name = "X-Total-Count", description = "Общее количество организаций"))
    public ResponseEntity<PageResponse<OrganizationResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        OrganizationPageResult result = organizationService.listOrganizations(Pagination.pageRequest(page, size));
        return ResponseEntity.ok()
                .header("X-Total-Count", Long.toString(result.totalCount()))
                .body(result.body());
    }

    @PostMapping("/{organizationId}/delivery-points")
    @Operation(summary = "Добавить точку выдачи организации")
    @ApiResponse(responseCode = "201", description = "Точка выдачи создана")
    public ResponseEntity<DeliveryPointResponse> createDeliveryPoint(
            @PathVariable UUID organizationId,
            @Valid @RequestBody CreateDeliveryPointRequest request) {
        DeliveryPointResponse response = organizationService.createDeliveryPoint(organizationId, request);
        return ResponseEntity.created(URI.create("/api/v1/delivery-points/" + response.id())).body(response);
    }

    @GetMapping("/{organizationId}/delivery-points")
    @Operation(summary = "Получить страницу точек выдачи организации")
    public ResponseEntity<PageResponse<DeliveryPointResponse>> listDeliveryPoints(
            @PathVariable UUID organizationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(organizationService.listDeliveryPoints(
                organizationId,
                Pagination.pageRequest(page, size)));
    }
}
