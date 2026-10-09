package ru.itmo.highload.catering.kitchen.client;

import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import ru.itmo.highload.catering.kitchen.client.dto.in.KitchenCommand;
import ru.itmo.highload.catering.kitchen.client.dto.in.OrderStatesRequest;
import ru.itmo.highload.catering.kitchen.client.dto.out.OrderResponse;
import ru.itmo.highload.catering.kitchen.client.dto.out.OrderState;
import ru.itmo.highload.common.dto.out.PageResponse;

@FeignClient(name = "order-service", url = "${clients.order.url:}")
public interface OrderClient {
    @PostMapping("/internal/v1/orders/states")
    List<OrderState> states(
            @RequestBody OrderStatesRequest request,
            @RequestHeader("Authorization") String bearer, @RequestHeader("X-Trace-Id") String trace);

    @GetMapping("/internal/v1/orders/{id}")
    OrderResponse get(@PathVariable UUID id, @RequestHeader("Authorization") String bearer, @RequestHeader("X-Trace-Id") String trace);

    @GetMapping("/internal/v1/orders/kitchen")
    PageResponse<OrderResponse> queue(@RequestParam int page, @RequestParam int size,
                                     @RequestHeader("Authorization") String bearer, @RequestHeader("X-Trace-Id") String trace);

    @PostMapping("/internal/v1/orders/{id}/kitchen-commands")
    OrderResponse command(@PathVariable UUID id, @RequestBody KitchenCommand command,
                          @RequestHeader("Authorization") String bearer, @RequestHeader("X-Trace-Id") String trace);
}
