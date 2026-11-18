package ru.itmo.highload.catering.order.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ru.itmo.highload.catering.order.application.ports.HistoryStore;
import ru.itmo.highload.catering.order.application.ports.OrderStore;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import ru.itmo.highload.catering.catalog.application.dto.out.ActiveDishData;
import ru.itmo.highload.catering.catalog.application.ports.DishCatalog;
import ru.itmo.highload.catering.order.application.dto.in.CancelOrderRequest;
import ru.itmo.highload.catering.order.application.dto.in.CreateOrderRequest;
import ru.itmo.highload.catering.order.application.dto.in.OrderLineInput;
import ru.itmo.highload.catering.order.application.dto.in.RejectOrderRequest;
import ru.itmo.highload.catering.order.application.dto.in.ReplaceOrderLinesRequest;
import ru.itmo.highload.catering.order.application.dto.in.UpdateOrderDetailsRequest;
import ru.itmo.highload.catering.order.application.dto.out.OrderLineResponse;
import ru.itmo.highload.catering.order.application.dto.out.OrderPageResult;
import ru.itmo.highload.catering.order.application.dto.out.OrderResponse;
import ru.itmo.highload.catering.order.application.dto.out.OrderState;
import ru.itmo.highload.catering.order.application.dto.out.OrderStatusHistoryResponse;
import ru.itmo.highload.catering.order.domain.model.CorporateOrder;
import ru.itmo.highload.catering.order.domain.model.OrderLine;
import ru.itmo.highload.catering.order.domain.model.OrderStatus;
import ru.itmo.highload.catering.order.domain.model.OrderStatusHistory;
import ru.itmo.highload.catering.organization.application.OrganizationService;
import ru.itmo.highload.common.dto.out.PageResponse;
import ru.itmo.highload.common.domain.*;

@RequiredArgsConstructor
public class OrderService {

    private final OrderStore orderRepository;
    private final HistoryStore historyRepository;
    private final OrganizationService organizationService;
    private final DishCatalog catalogGateway;
    private final Clock clock;

    public OrderResponse createDraft(CreateOrderRequest request) {
        return createDraft(request, null);
    }

    public OrderResponse createDraft(CreateOrderRequest request, OrderAccess access) {
        UUID organizationId = organizationForCreate(request.organizationId(), access);
        organizationService.requireActiveOrganizationAndPoint(
                organizationId,
                request.deliveryPointId());
        CorporateOrder order = new CorporateOrder(
                organizationId,
                request.deliveryPointId(),
                request.requestedDeliveryAt().toInstant(),
                request.comment(),
                clock.instant());
        return toResponse(orderRepository.saveAndFlush(order));
    }

    public OrderResponse getOrder(UUID id) {
        return getOrder(id, null);
    }

    public OrderResponse getOrder(UUID id, OrderAccess access) {
        return toResponse(requireOrder(id, access));
    }

    public List<OrderState> states(Set<UUID> ids) {
        return states(ids, null);
    }

    public List<OrderState> states(Set<UUID> ids, OrderAccess access) {
        var found = orderRepository.findAllById(ids);
        if (found.size() != ids.size()) {
            throw new BusinessFailure(BusinessFailure.Kind.NOT_FOUND, "RESOURCE_NOT_FOUND", "Один из заказов не найден");
        }
        found.forEach(order -> requireVisible(order, access));
        return found.stream().map(order -> new OrderState(
                order.getId(), order.getStatus(), order.getVersion())).toList();
    }

    public OrderPageResult listOrders(
            int page,
            int size,
            OrderStatus status,
            UUID organizationId) {
        return listOrders(page, size, status, organizationId, null);
    }

    public OrderPageResult listOrders(
            int page,
            int size,
            OrderStatus status,
            UUID organizationId,
            OrderAccess access) {
        UUID effectiveOrganizationId = organizationForFilter(organizationId, access);
        if (effectiveOrganizationId != null) {
            organizationService.getOrganization(effectiveOrganizationId);
        }
        PageQuery pageRequest = PageQuery.of(
                page,
                size,
                "createdAt DESC,id DESC");
        PageResult<CorporateOrder> orders = orderRepository.findPage(
                status, effectiveOrganizationId, access != null && access.hidesDraftOrders(), pageRequest);
        PageResponse<OrderResponse> body = new PageResponse<>(
                orders.getContent().stream().map(this::toResponse).toList(),
                orders.getNumber(),
                orders.getSize(),
                orders.hasNext());
        return new OrderPageResult(body, orders.getTotalElements());
    }

    public PageResponse<OrderResponse> kitchenQueue(PageQuery request) {
        return kitchenQueue(request, null);
    }

    public PageResponse<OrderResponse> kitchenQueue(PageQuery request, OrderAccess access) {
        PageResult<CorporateOrder> page = orderRepository.findKitchenPage(
                Set.of(OrderStatus.CONFIRMED, OrderStatus.IN_COOKING, OrderStatus.READY),
                access != null && access.restrictsToOrganization() ? access.organizationId() : null,
                request.withSort("id ASC"));
        return new PageResponse<>(page.getContent().stream().map(this::toResponse).toList(),
                page.getNumber(), page.getSize(), page.hasNext());
    }

    public OrderResponse updateDraftDetails(UUID id, UpdateOrderDetailsRequest request) {
        return updateDraftDetails(id, request, null);
    }

    public OrderResponse updateDraftDetails(UUID id, UpdateOrderDetailsRequest request, OrderAccess access) {
        CorporateOrder order = requireOrder(id, access);
        requireExpectedVersion(order, request.expectedVersion());
        requireDraftStatus(order, "Детали заказа можно менять только в статусе DRAFT");
        organizationService.requireActiveOrganizationAndPoint(
                order.getOrganizationId(),
                request.deliveryPointId());
        order.updateDetails(
                request.deliveryPointId(),
                request.requestedDeliveryAt().toInstant(),
                request.comment());
        return flushAndMap(order);
    }

    public OrderResponse replaceDraftLines(UUID id, ReplaceOrderLinesRequest request, String bearer) {
        return replaceDraftLines(id, request, bearer, null);
    }

    public OrderResponse replaceDraftLines(UUID id, ReplaceOrderLinesRequest request, String bearer, OrderAccess access) {
        CorporateOrder order = requireOrder(id, access);
        requireExpectedVersion(order, request.expectedVersion());
        requireDraftStatus(order, "Состав заказа можно менять только в статусе DRAFT");
        ensureNoDuplicateDishes(request.lines());

        Set<UUID> dishIds = request.lines().stream()
                .map(OrderLineInput::dishId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, ActiveDishData> activeDishes = catalogGateway.getActiveDishPrices(dishIds, bearer);
        List<CorporateOrder.DraftLine> replacements = request.lines().stream()
                .map(line -> {
                    ActiveDishData dish = activeDishes.get(line.dishId());
                    return new CorporateOrder.DraftLine(line.dishId(), line.quantity(), dish.currentPrice());
                })
                .toList();
        order.replaceLines(replacements);
        return flushAndMap(order);
    }

    public void deleteEmptyDraft(UUID id) {
        deleteEmptyDraft(id, null);
    }

    public void deleteEmptyDraft(UUID id, OrderAccess access) {
        CorporateOrder order = requireOrder(id, access);
        try {
            order.requireDeletable();
            orderRepository.delete(order);
            orderRepository.flush();
        } catch (CorporateOrder.OrderStatusException exception) {
            throw statusConflict(exception.getMessage());
        } catch (CorporateOrder.NonEmptyOrderDeleteException exception) {
            throw new BusinessFailure(
                    BusinessFailure.Kind.REJECTED,
                    "ORDER_DELETE_FORBIDDEN",
                    exception.getMessage());
        }
    }

    public OrderResponse submit(UUID id, long expectedVersion, String bearer) {
        return submit(id, expectedVersion, bearer, null);
    }

    public OrderResponse submit(UUID id, long expectedVersion, String bearer, OrderAccess access) {
        CorporateOrder order = requireOrder(id, access);
        requireExpectedVersion(order, expectedVersion);
        try {
            organizationService.requireActiveOrganizationAndPoint(
                    order.getOrganizationId(),
                    order.getDeliveryPointId());
            Set<UUID> dishIds = order.getLines().stream()
                    .map(OrderLine::getDishId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<UUID, ActiveDishData> activeDishes = catalogGateway.getActiveDishPrices(dishIds, bearer);
            Map<UUID, CorporateOrder.DishSnapshot> snapshots = activeDishes.values().stream()
                    .collect(Collectors.toMap(
                            ActiveDishData::id,
                            dish -> new CorporateOrder.DishSnapshot(dish.name(), dish.currentPrice()),
                            (left, right) -> left,
                            LinkedHashMap::new));
            order.submit(snapshots, clock.instant(), changedBy(access));
            return flushAndMap(order);
        } catch (CorporateOrder.EmptyOrderException exception) {
            throw new BusinessFailure(BusinessFailure.Kind.REJECTED, "ORDER_EMPTY", exception.getMessage());
        } catch (CorporateOrder.DeliveryTimeNotFutureException exception) {
            throw new BusinessFailure(
                    BusinessFailure.Kind.REJECTED,
                    "DELIVERY_TIME_NOT_FUTURE",
                    exception.getMessage());
        } catch (CorporateOrder.OrderStatusException exception) {
            throw statusConflict(exception.getMessage());
        }
    }

    public OrderResponse confirm(UUID id, long expectedVersion) {
        return confirm(id, expectedVersion, null);
    }

    public OrderResponse confirm(UUID id, long expectedVersion, OrderAccess access) {
        return executeStatusCommand(id, expectedVersion, access, order -> order.confirm(clock.instant(), changedBy(access)));
    }

    public OrderResponse reject(UUID id, RejectOrderRequest request) {
        return reject(id, request, null);
    }

    public OrderResponse reject(UUID id, RejectOrderRequest request, OrderAccess access) {
        return executeStatusCommand(
                id,
                request.expectedVersion(),
                access,
                order -> order.reject(request.reason(), clock.instant(), changedBy(access)));
    }

    public OrderResponse cancel(UUID id, CancelOrderRequest request) {
        return cancel(id, request, null);
    }

    public OrderResponse cancel(UUID id, CancelOrderRequest request, OrderAccess access) {
        return executeStatusCommand(
                id,
                request.expectedVersion(),
                access,
                order -> order.cancel(request.reason(), clock.instant(), changedBy(access)));
    }

    public OrderResponse startCooking(UUID id, long expectedVersion) {
        return startCooking(id, expectedVersion, null);
    }

    public OrderResponse startCooking(UUID id, long expectedVersion, OrderAccess access) {
        return executeStatusCommand(id, expectedVersion, access, order -> order.startCooking(clock.instant(), changedBy(access)));
    }

    public OrderResponse markReady(UUID id, long expectedVersion) {
        return markReady(id, expectedVersion, null);
    }

    public OrderResponse markReady(UUID id, long expectedVersion, OrderAccess access) {
        return executeStatusCommand(id, expectedVersion, access, order -> order.markReady(clock.instant(), changedBy(access)));
    }

    public OrderResponse complete(UUID id, long expectedVersion) {
        return complete(id, expectedVersion, null);
    }

    public OrderResponse complete(UUID id, long expectedVersion, OrderAccess access) {
        return executeStatusCommand(id, expectedVersion, access, order -> order.complete(clock.instant(), changedBy(access)));
    }

    public PageResponse<OrderStatusHistoryResponse> getHistory(UUID id, PageQuery pageRequest) {
        return getHistory(id, pageRequest, null);
    }

    public PageResponse<OrderStatusHistoryResponse> getHistory(UUID id, PageQuery pageRequest, OrderAccess access) {
        requireOrder(id, access);
        PageResult<OrderStatusHistory> history = historyRepository.findByOrder_Id(
                id,
                pageRequest.withSort("changedAt ASC,id ASC"));
        return new PageResponse<>(
                history.getContent().stream().map(this::toResponse).toList(),
                history.getNumber(),
                history.getSize(),
                history.hasNext());
    }

    private CorporateOrder requireOrder(UUID id) {
        return requireOrder(id, null);
    }

    private CorporateOrder requireOrder(UUID id, OrderAccess access) {
        CorporateOrder order = orderRepository.findDetailedById(id)
                .orElseThrow(() -> orderNotFound(id));
        requireVisible(order, access);
        return order;
    }

    private void requireVisible(CorporateOrder order, OrderAccess access) {
        if (access != null && ((access.restrictsToOrganization() && !access.organizationId().equals(order.getOrganizationId()))
                || (access.hidesDraftOrders() && order.getStatus() == OrderStatus.DRAFT))) {
            throw orderNotFound(order.getId());
        }
    }

    private UUID organizationForCreate(UUID requestedOrganizationId, OrderAccess access) {
        if (access != null && access.restrictsToOrganization()) {
            if (requestedOrganizationId != null && !access.organizationId().equals(requestedOrganizationId)) {
                throw orderNotFound(requestedOrganizationId);
            }
            return access.organizationId();
        }
        if (requestedOrganizationId == null) {
            throw new BusinessFailure(BusinessFailure.Kind.VALIDATION, "VALIDATION_FAILED", "Организация обязательна");
        }
        return requestedOrganizationId;
    }

    private UUID organizationForFilter(UUID requestedOrganizationId, OrderAccess access) {
        if (access != null && access.restrictsToOrganization()) {
            if (requestedOrganizationId != null && !access.organizationId().equals(requestedOrganizationId)) {
                throw orderNotFound(requestedOrganizationId);
            }
            return access.organizationId();
        }
        return requestedOrganizationId;
    }

    private UUID changedBy(OrderAccess access) {
        return access == null ? null : access.userId();
    }

    private OrderResponse executeStatusCommand(
            UUID id,
            long expectedVersion,
            OrderAccess access,
            Consumer<CorporateOrder> command) {
        CorporateOrder order = requireOrder(id, access);
        requireExpectedVersion(order, expectedVersion);
        try {
            command.accept(order);
            return flushAndMap(order);
        } catch (CorporateOrder.OrderStatusException exception) {
            throw statusConflict(exception.getMessage());
        }
    }

    private BusinessFailure orderNotFound(UUID id) {
        return new BusinessFailure(
                BusinessFailure.Kind.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "Заказ с идентификатором " + id + " не найден");
    }

    private void requireExpectedVersion(CorporateOrder order, long expectedVersion) {
        if (order.getVersion() != expectedVersion) {
            throw versionConflict();
        }
    }

    private void requireDraftStatus(CorporateOrder order, String message) {
        if (order.getStatus() != OrderStatus.DRAFT) {
            throw statusConflict(message);
        }
    }

    private void ensureNoDuplicateDishes(List<OrderLineInput> lines) {
        Set<UUID> seen = new LinkedHashSet<>();
        for (OrderLineInput line : lines) {
            if (!seen.add(line.dishId())) {
                throw new BusinessFailure(
                        BusinessFailure.Kind.REJECTED,
                        "DUPLICATE_DISH",
                        "Блюдо " + line.dishId() + " повторяется в заказе");
            }
        }
    }

    private OrderResponse flushAndMap(CorporateOrder order) {
        try {
            orderRepository.flush();
            return toResponse(order);
        } catch (ConcurrentWrite exception) {
            throw versionConflict();
        } catch (CorporateOrder.OrderStatusException exception) {
            throw statusConflict(exception.getMessage());
        }
    }

    private BusinessFailure versionConflict() {
        return new BusinessFailure(
                BusinessFailure.Kind.CONFLICT,
                "ORDER_VERSION_CONFLICT",
                "Версия заказа устарела; перечитайте актуальное состояние");
    }

    private BusinessFailure statusConflict(String message) {
        return new BusinessFailure(BusinessFailure.Kind.CONFLICT, "ORDER_STATUS_CONFLICT", message);
    }

    private OrderResponse toResponse(CorporateOrder order) {
        return new OrderResponse(
                order.getId(),
                order.getOrganizationId(),
                order.getDeliveryPointId(),
                OffsetDateTime.ofInstant(order.getRequestedDeliveryAt(), ZoneOffset.UTC),
                order.getStatus(),
                order.getTotalAmount(),
                order.getComment(),
                order.getVersion(),
                OffsetDateTime.ofInstant(order.getCreatedAt(), ZoneOffset.UTC),
                order.getLines().stream().map(this::toResponse).toList());
    }

    private OrderLineResponse toResponse(OrderLine line) {
        return new OrderLineResponse(
                line.getId(),
                line.getDishId(),
                line.getDishNameSnapshot(),
                line.getQuantity(),
                line.getUnitPriceSnapshot(),
                line.getLineAmount());
    }

    private OrderStatusHistoryResponse toResponse(OrderStatusHistory history) {
        return new OrderStatusHistoryResponse(
                history.getId(),
                history.getOrderId(),
                history.getFromStatus(),
                history.getToStatus(),
                history.getReason(),
                history.getChangedBy(),
                OffsetDateTime.ofInstant(history.getChangedAt(), ZoneOffset.UTC));
    }
}
