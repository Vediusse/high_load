package ru.itmo.highload.catering.catalog.adapters.in.rest;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import ru.itmo.highload.catering.catalog.application.dto.in.SnapshotRequest;
import ru.itmo.highload.catering.catalog.application.dto.out.DishSnapshot;
import ru.itmo.highload.catering.catalog.application.CatalogService;
@Hidden
@RestController
public class SnapshotController {
    private final CatalogService catalog;
    public SnapshotController(CatalogService catalog) { this.catalog = catalog; }
    @PostMapping("/internal/v1/dishes/snapshots")
    @PreAuthorize("hasAnyRole('ORGANIZATION_REPRESENTATIVE', 'CLIENT_MANAGER')")
    public Flux<DishSnapshot> snapshots(@Valid @RequestBody SnapshotRequest request) {
        return catalog.snapshots(request.ids());
    }
}
