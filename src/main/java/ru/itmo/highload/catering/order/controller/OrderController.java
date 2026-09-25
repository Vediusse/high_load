package ru.itmo.highload.catering.order.controller;

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

    private final OrderService orderService;

    @PostMapping
    @Operation(summary = "Создать пустой черновик заказа")
    @ApiResponse(responseCode = "201", description = "Черновик создан")
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        OrderResponse response = orderService.createDraft(request);
        return ResponseEntity.created(URI.create("/api/v1/orders/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Получить заказ с позициями")
    public ResponseEntity<OrderResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.getOrder(id));
    }

    @GetMapping
    @Operation(summary = "Получить страницу заказов с фильтрами")
    @ApiResponse(
            responseCode = "200",
            description = "Страница заказов",
            headers = @Header(name = "X-Total-Count", description = "Общее количество заказов по фильтру"))
    public ResponseEntity<PageResponse<OrderResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) UUID organizationId) {
        PageRequestValues pageRequest = validatePage(page, size);
        OrderPageResult result = orderService.listOrders(
                pageRequest.page(),
                pageRequest.size(),
                status,
                organizationId);
        return ResponseEntity.ok()
                .header("X-Total-Count", Long.toString(result.totalCount()))
                .body(result.body());
    }

    @PutMapping("/{id}/details")
    @Operation(summary = "Изменить детали черновика")
    public ResponseEntity<OrderResponse> updateDetails(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateOrderDetailsRequest request) {
        return ResponseEntity.ok(orderService.updateDraftDetails(id, request));
    }

    @PutMapping("/{id}/lines")
    @Operation(summary = "Атомарно заменить позиции черновика")
    public ResponseEntity<OrderResponse> replaceLines(
            @PathVariable UUID id,
            @Valid @RequestBody ReplaceOrderLinesRequest request) {
        return ResponseEntity.ok(orderService.replaceDraftLines(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить пустой черновик")
    @ApiResponse(responseCode = "204", description = "Пустой черновик удалён")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        orderService.deleteEmptyDraft(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Отправить заказ и зафиксировать снимки цен")
    public ResponseEntity<OrderResponse> submit(
            @PathVariable UUID id,
            @Valid @RequestBody OrderCommandRequest request) {
        return ResponseEntity.ok(orderService.submit(id, request.expectedVersion()));
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Подтвердить заказ целиком")
    public ResponseEntity<OrderResponse> confirm(
            @PathVariable UUID id,
            @Valid @RequestBody OrderCommandRequest request) {
        return ResponseEntity.ok(orderService.confirm(id, request.expectedVersion()));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Отклонить заказ целиком с причиной")
    public ResponseEntity<OrderResponse> reject(
            @PathVariable UUID id,
            @Valid @RequestBody RejectOrderRequest request) {
        return ResponseEntity.ok(orderService.reject(id, request));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Отменить заказ до начала приготовления")
    public ResponseEntity<OrderResponse> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody CancelOrderRequest request) {
        return ResponseEntity.ok(orderService.cancel(id, request));
    }

    @PostMapping("/{id}/start-cooking")
    @Operation(summary = "Начать приготовление подтверждённого заказа")
    public ResponseEntity<OrderResponse> startCooking(
            @PathVariable UUID id,
            @Valid @RequestBody OrderCommandRequest request) {
        return ResponseEntity.ok(orderService.startCooking(id, request.expectedVersion()));
    }

    @PostMapping("/{id}/mark-ready")
    @Operation(summary = "Отметить заказ готовым")
    public ResponseEntity<OrderResponse> markReady(
            @PathVariable UUID id,
            @Valid @RequestBody OrderCommandRequest request) {
        return ResponseEntity.ok(orderService.markReady(id, request.expectedVersion()));
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "Завершить выданный заказ")
    public ResponseEntity<OrderResponse> complete(
            @PathVariable UUID id,
            @Valid @RequestBody OrderCommandRequest request) {
        return ResponseEntity.ok(orderService.complete(id, request.expectedVersion()));
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Получить хронологическую страницу истории статусов")
    public ResponseEntity<PageResponse<OrderStatusHistoryResponse>> history(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(orderService.getHistory(id, Pagination.pageRequest(page, size)));
    }

    private PageRequestValues validatePage(int page, int size) {
        var pageRequest = Pagination.pageRequest(page, size);
        return new PageRequestValues(pageRequest.getPageNumber(), pageRequest.getPageSize());
    }

    private record PageRequestValues(int page, int size) {
    }
}
