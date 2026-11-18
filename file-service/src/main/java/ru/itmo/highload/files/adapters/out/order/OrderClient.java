package ru.itmo.highload.files.adapters.out.order;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import ru.itmo.highload.files.application.OrderSnapshot;
@FeignClient(name="order-service",url="${clients.order.url:}")
public interface OrderClient {
    @GetMapping("/internal/v1/orders/{id}/file-access")
    OrderSnapshot access(@PathVariable UUID id,@RequestHeader("Authorization") String bearer,@RequestHeader("X-Trace-Id") String trace);
}
