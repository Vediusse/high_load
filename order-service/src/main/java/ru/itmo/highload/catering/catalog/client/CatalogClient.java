package ru.itmo.highload.catering.catalog.client;

import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import ru.itmo.highload.catering.catalog.client.dto.in.SnapshotRequest;
import ru.itmo.highload.catering.catalog.client.dto.out.DishSnapshot;

@FeignClient(name = "catalog-service", url = "${clients.catalog.url:}")
public interface CatalogClient {
    int MAX_SNAPSHOT_IDS = 1000;

    @PostMapping("/internal/v1/dishes/snapshots")
    List<DishSnapshot> snapshots(@RequestBody SnapshotRequest request, @RequestHeader("Authorization") String bearer, @RequestHeader("X-Trace-Id") String traceId);
}
