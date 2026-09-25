package ru.itmo.highload.catering.catalog.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "catalog-service", url = "${clients.catalog.url:}")
public interface CatalogClient {
    @PostMapping("/internal/v1/dishes/snapshots")
    List<DishSnapshot> snapshots(@RequestBody SnapshotRequest request, @RequestHeader("X-Trace-Id") String traceId);
    record SnapshotRequest(Set<UUID> ids) { }
    record DishSnapshot(UUID id, String name, BigDecimal price, boolean active) { }
}
