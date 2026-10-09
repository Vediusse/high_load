package ru.itmo.highload.catering.catalog.controller;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.catalog.dto.DishSnapshot;
import ru.itmo.highload.catering.catalog.service.CatalogService;
@Hidden
@RestController
public class SnapshotController {
    private final CatalogService catalog;
    public SnapshotController(CatalogService catalog) { this.catalog = catalog; }
    @PostMapping("/internal/v1/dishes/snapshots")
    public Mono<List<DishSnapshot>> snapshots(@Valid @RequestBody SnapshotRequest request) {
        return catalog.snapshots(request.ids());
    }
    public record SnapshotRequest(@NotNull @Size(max = 1000) Set<@NotNull UUID> ids) { }
}
