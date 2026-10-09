package ru.itmo.highload.catering.order.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import ru.itmo.highload.catering.order.dto.in.CancelOrderRequest;
import ru.itmo.highload.catering.order.dto.in.CreateOrderRequest;
import ru.itmo.highload.catering.order.dto.in.OrderCommandRequest;
import ru.itmo.highload.catering.order.dto.in.RejectOrderRequest;
import ru.itmo.highload.catering.order.dto.in.ReplaceOrderLinesRequest;
import ru.itmo.highload.catering.order.dto.in.UpdateOrderDetailsRequest;
import ru.itmo.highload.catering.order.dto.out.OrderPageResult;
import ru.itmo.highload.catering.order.dto.out.OrderResponse;
import ru.itmo.highload.catering.order.dto.out.OrderStatusHistoryResponse;
import ru.itmo.highload.catering.order.entity.OrderStatus;
import ru.itmo.highload.catering.order.service.OrderService;
import ru.itmo.highload.catering.order.service.OrderAccess;
import ru.itmo.highload.common.config.StandardApiErrors;
import ru.itmo.highload.common.dto.out.PageResponse;
import ru.itmo.highload.common.web.BlockingRequests;
import ru.itmo.highload.common.web.Pagination;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Заказы")
@StandardApiErrors
@RequiredArgsConstructor
public class OrderController {

    private final BlockingRequests blocking;

    private final OrderService orderService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ORGANIZATION_REPRESENTATIVE', 'CLIENT_MANAGER')")
    @Operation(summary = "Создать пустой черновик заказа")
    @ApiResponse(responseCode = "201", description = "Черновик создан")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<OrderResponse> create(
            @Valid @RequestBody CreateOrderRequest request, ServerHttpResponse httpResponse, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> {
            OrderResponse response = orderService.createDraft(request, OrderAccess.from(actor));
            httpResponse.getHeaders().setLocation(URI.create("/api/v1/orders/" + response.id()));
            return response;
        });
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ORGANIZATION_REPRESENTATIVE', 'CLIENT_MANAGER', 'KITCHEN_MANAGER')")
    @Operation(summary = "Получить заказ с позициями")
    public Mono<OrderResponse> get(@PathVariable UUID id, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> orderService.getOrder(id, OrderAccess.from(actor)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ORGANIZATION_REPRESENTATIVE', 'CLIENT_MANAGER', 'KITCHEN_MANAGER')")
    @Operation(summary = "Получить страницу заказов с фильтрами")
    @ApiResponse(
            responseCode = "200",
            description = "Страница заказов",
            headers = @Header(name = "X-Total-Count", description = "Общее количество заказов по фильтру"))
    public Mono<PageResponse<OrderResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) UUID organizationId, ServerHttpResponse httpResponse, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> {
            PageRequestValues pageRequest = validatePage(page, size);
            OrderPageResult result = orderService.listOrders(
                    pageRequest.page(),
                    pageRequest.size(),
                    status,
                    organizationId,
                    OrderAccess.from(actor));
            httpResponse.getHeaders().set("X-Total-Count", Long.toString(result.totalCount()));
            return result.body();
        });
    }

    @PutMapping("/{id}/details")
    @PreAuthorize("hasAnyRole('ORGANIZATION_REPRESENTATIVE', 'CLIENT_MANAGER')")
    @Operation(summary = "Изменить детали черновика")
    public Mono<OrderResponse> updateDetails(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateOrderDetailsRequest request, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> orderService.updateDraftDetails(id, request, OrderAccess.from(actor)));
    }

    @PutMapping("/{id}/lines")
    @PreAuthorize("hasAnyRole('ORGANIZATION_REPRESENTATIVE', 'CLIENT_MANAGER')")
    @Operation(summary = "Атомарно заменить позиции черновика")
    public Mono<OrderResponse> replaceLines(
            @PathVariable UUID id,
            @Valid @RequestBody ReplaceOrderLinesRequest request, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> orderService.replaceDraftLines(
                id, request, "Bearer " + actor.getTokenValue(), OrderAccess.from(actor)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ORGANIZATION_REPRESENTATIVE', 'CLIENT_MANAGER')")
    @Operation(summary = "Удалить пустой черновик")
    @ApiResponse(responseCode = "204", description = "Пустой черновик удалён")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> {
            orderService.deleteEmptyDraft(id, OrderAccess.from(actor));
            return null;
        });
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('ORGANIZATION_REPRESENTATIVE', 'CLIENT_MANAGER')")
    @Operation(summary = "Отправить заказ и зафиксировать снимки цен")
    public Mono<OrderResponse> submit(
            @PathVariable UUID id,
            @Valid @RequestBody OrderCommandRequest request, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> orderService.submit(
                id, request.expectedVersion(), "Bearer " + actor.getTokenValue(), OrderAccess.from(actor)));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('KITCHEN_MANAGER')")
    @Operation(summary = "Подтвердить заказ целиком")
    public Mono<OrderResponse> confirm(
            @PathVariable UUID id,
            @Valid @RequestBody OrderCommandRequest request, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> orderService.confirm(id, request.expectedVersion(), OrderAccess.from(actor)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('KITCHEN_MANAGER')")
    @Operation(summary = "Отклонить заказ целиком с причиной")
    public Mono<OrderResponse> reject(
            @PathVariable UUID id,
            @Valid @RequestBody RejectOrderRequest request, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> orderService.reject(id, request, OrderAccess.from(actor)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ORGANIZATION_REPRESENTATIVE', 'CLIENT_MANAGER')")
    @Operation(summary = "Отменить заказ до начала приготовления")
    public Mono<OrderResponse> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody CancelOrderRequest request, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> orderService.cancel(id, request, OrderAccess.from(actor)));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAnyRole('ORGANIZATION_REPRESENTATIVE', 'CLIENT_MANAGER', 'KITCHEN_MANAGER')")
    @Operation(summary = "Получить хронологическую страницу истории статусов")
    public Mono<PageResponse<OrderStatusHistoryResponse>> history(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> orderService.getHistory(id, Pagination.pageRequest(page, size), OrderAccess.from(actor)));
    }

    private PageRequestValues validatePage(int page, int size) {
        var pageRequest = Pagination.pageRequest(page, size);
        return new PageRequestValues(pageRequest.getPageNumber(), pageRequest.getPageSize());
    }

    private record PageRequestValues(int page, int size) {
    }
}
