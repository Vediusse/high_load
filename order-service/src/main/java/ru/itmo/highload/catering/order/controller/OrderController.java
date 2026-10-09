package ru.itmo.highload.catering.order.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.itmo.highload.common.web.BlockingRequests;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.itmo.highload.common.dto.PageResponse;
import ru.itmo.highload.common.config.StandardApiErrors;
import ru.itmo.highload.common.web.Pagination;
import ru.itmo.highload.catering.order.dto.CreateOrderRequest;
import ru.itmo.highload.catering.order.dto.CancelOrderRequest;
import ru.itmo.highload.catering.order.dto.OrderCommandRequest;
import ru.itmo.highload.catering.order.dto.OrderPageResult;
import ru.itmo.highload.catering.order.dto.OrderResponse;
import ru.itmo.highload.catering.order.dto.OrderStatusHistoryResponse;
import ru.itmo.highload.catering.order.dto.RejectOrderRequest;
import ru.itmo.highload.catering.order.dto.ReplaceOrderLinesRequest;
import ru.itmo.highload.catering.order.dto.UpdateOrderDetailsRequest;
import ru.itmo.highload.catering.order.entity.OrderStatus;
import ru.itmo.highload.catering.order.service.OrderService;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Заказы")
@StandardApiErrors
@RequiredArgsConstructor
public class OrderController {

    private final BlockingRequests blocking;

    private final OrderService orderService;

    @PostMapping
    @Operation(summary = "Создать пустой черновик заказа")
    @ApiResponse(responseCode = "201", description = "Черновик создан")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request, ServerHttpResponse httpResponse) {
        return blocking.call(() -> {
            OrderResponse response = orderService.createDraft(request);
            httpResponse.getHeaders().setLocation(URI.create("/api/v1/orders/" + response.id()));
            return response;
        });
    }

    @GetMapping("/{id}")
    @Operation(summary = "Получить заказ с позициями")
    public Mono<OrderResponse> get(@PathVariable UUID id) {
        return blocking.call(() -> orderService.getOrder(id));
    }

    @GetMapping
    @Operation(summary = "Получить страницу заказов с фильтрами")
    @ApiResponse(
            responseCode = "200",
            description = "Страница заказов",
            headers = @Header(name = "X-Total-Count", description = "Общее количество заказов по фильтру"))
    public Mono<PageResponse<OrderResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) UUID organizationId, ServerHttpResponse httpResponse) {
        return blocking.call(() -> {
            PageRequestValues pageRequest = validatePage(page, size);
            OrderPageResult result = orderService.listOrders(
                    pageRequest.page(),
                    pageRequest.size(),
                    status,
                    organizationId);
            httpResponse.getHeaders().set("X-Total-Count", Long.toString(result.totalCount()));
            return result.body();
        });
    }

    @PutMapping("/{id}/details")
    @Operation(summary = "Изменить детали черновика")
    public Mono<OrderResponse> updateDetails(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateOrderDetailsRequest request) {
        return blocking.call(() -> orderService.updateDraftDetails(id, request));
    }

    @PutMapping("/{id}/lines")
    @Operation(summary = "Атомарно заменить позиции черновика")
    public Mono<OrderResponse> replaceLines(
            @PathVariable UUID id,
            @Valid @RequestBody ReplaceOrderLinesRequest request) {
        return blocking.call(() -> orderService.replaceDraftLines(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить пустой черновик")
    @ApiResponse(responseCode = "204", description = "Пустой черновик удалён")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return blocking.call(() -> {
            orderService.deleteEmptyDraft(id);
            return null;
        });
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Отправить заказ и зафиксировать снимки цен")
    public Mono<OrderResponse> submit(
            @PathVariable UUID id,
            @Valid @RequestBody OrderCommandRequest request) {
        return blocking.call(() -> orderService.submit(id, request.expectedVersion()));
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Подтвердить заказ целиком")
    public Mono<OrderResponse> confirm(
            @PathVariable UUID id,
            @Valid @RequestBody OrderCommandRequest request) {
        return blocking.call(() -> orderService.confirm(id, request.expectedVersion()));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Отклонить заказ целиком с причиной")
    public Mono<OrderResponse> reject(
            @PathVariable UUID id,
            @Valid @RequestBody RejectOrderRequest request) {
        return blocking.call(() -> orderService.reject(id, request));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Отменить заказ до начала приготовления")
    public Mono<OrderResponse> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody CancelOrderRequest request) {
        return blocking.call(() -> orderService.cancel(id, request));
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Получить хронологическую страницу истории статусов")
    public Mono<PageResponse<OrderStatusHistoryResponse>> history(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return blocking.call(() -> orderService.getHistory(id, Pagination.pageRequest(page, size)));
    }

    private PageRequestValues validatePage(int page, int size) {
        var pageRequest = Pagination.pageRequest(page, size);
        return new PageRequestValues(pageRequest.getPageNumber(), pageRequest.getPageSize());
    }

    private record PageRequestValues(int page, int size) {
    }
}
