package ru.itmo.highload.catering.catalog.adapters.in.rest;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import ru.itmo.highload.catering.catalog.application.CatalogService;
import ru.itmo.highload.catering.catalog.application.dto.in.SnapshotRequest;
import ru.itmo.highload.catering.catalog.application.dto.out.DishSnapshot;

@Hidden
@RestController
@RequiredArgsConstructor
public class SnapshotController {
    private final CatalogService catalog;

    @PostMapping("/internal/v1/dishes/snapshots")
    @PreAuthorize("hasAnyRole('ORGANIZATION_REPRESENTATIVE', 'CLIENT_MANAGER')")
    public Flux<DishSnapshot> snapshots(@Valid @RequestBody SnapshotRequest request) {
        return catalog.snapshots(request.ids());
    }
}
