package ru.itmo.highload.catering.organization.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.itmo.highload.catering.organization.dto.DeliveryPointResponse;
import ru.itmo.highload.catering.common.config.StandardApiErrors;
import ru.itmo.highload.catering.organization.dto.UpdateDeliveryPointRequest;
import ru.itmo.highload.catering.organization.service.OrganizationService;

@RestController
@RequestMapping("/api/v1/delivery-points")
@Tag(name = "Организации и точки выдачи")
@StandardApiErrors
@RequiredArgsConstructor
public class DeliveryPointController {

    private final OrganizationService organizationService;

    @PutMapping("/{id}")
    @Operation(summary = "Изменить точку выдачи")
    public ResponseEntity<DeliveryPointResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDeliveryPointRequest request) {
        return ResponseEntity.ok(organizationService.updateDeliveryPoint(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Деактивировать точку выдачи")
    @ApiResponse(responseCode = "204", description = "Точка выдачи деактивирована")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        organizationService.deactivateDeliveryPoint(id);
        return ResponseEntity.noContent().build();
    }
}
