package ru.itmo.highload.catering.order.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.highload.catering.catalog.dto.out.ActiveDishData;
import ru.itmo.highload.catering.catalog.service.CatalogGateway;
import ru.itmo.highload.catering.order.dto.in.CancelOrderRequest;
import ru.itmo.highload.catering.order.dto.in.CreateOrderRequest;
import ru.itmo.highload.catering.order.dto.in.OrderLineInput;
import ru.itmo.highload.catering.order.dto.in.RejectOrderRequest;
import ru.itmo.highload.catering.order.dto.in.ReplaceOrderLinesRequest;
import ru.itmo.highload.catering.order.dto.in.UpdateOrderDetailsRequest;
import ru.itmo.highload.catering.order.dto.out.OrderLineResponse;
import ru.itmo.highload.catering.order.dto.out.OrderPageResult;
import ru.itmo.highload.catering.order.dto.out.OrderResponse;
import ru.itmo.highload.catering.order.dto.out.OrderState;
import ru.itmo.highload.catering.order.dto.out.OrderStatusHistoryResponse;
import ru.itmo.highload.catering.order.entity.CorporateOrder;
import ru.itmo.highload.catering.order.entity.OrderLine;
import ru.itmo.highload.catering.order.entity.OrderStatus;
import ru.itmo.highload.catering.order.entity.OrderStatusHistory;
import ru.itmo.highload.catering.order.repository.CorporateOrderRepository;
import ru.itmo.highload.catering.order.repository.OrderStatusHistoryRepository;
import ru.itmo.highload.catering.organization.service.OrganizationService;
import ru.itmo.highload.common.dto.out.PageResponse;
import ru.itmo.highload.common.error.ApiException;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrderService {

    private final CorporateOrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final OrganizationService organizationService;
    private final CatalogGateway catalogGateway;
    private final Clock clock;

    @Transactional
    public OrderResponse createDraft(CreateOrderRequest request) {
        return createDraft(request, null);
    }

    @Transactional
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
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Один из заказов не найден");
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
        PageRequest pageRequest = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<CorporateOrder> orders = orderRepository.findPage(
                status, effectiveOrganizationId, access != null && access.hidesDraftOrders(), pageRequest);
        PageResponse<OrderResponse> body = new PageResponse<>(
                orders.getContent().stream().map(this::toResponse).toList(),
                orders.getNumber(),
                orders.getSize(),
                orders.hasNext());
        return new OrderPageResult(body, orders.getTotalElements());
    }

    public PageResponse<OrderResponse> kitchenQueue(PageRequest request) {
        return kitchenQueue(request, null);
    }

    public PageResponse<OrderResponse> kitchenQueue(PageRequest request, OrderAccess access) {
        Page<CorporateOrder> page = orderRepository.findKitchenPage(
                Set.of(OrderStatus.CONFIRMED, OrderStatus.IN_COOKING, OrderStatus.READY),
                access != null && access.restrictsToOrganization() ? access.organizationId() : null,
                request.withSort(Sort.by("id")));
        return new PageResponse<>(page.getContent().stream().map(this::toResponse).toList(),
                page.getNumber(), page.getSize(), page.hasNext());
    }

    @Transactional
    public OrderResponse updateDraftDetails(UUID id, UpdateOrderDetailsRequest request) {
        return updateDraftDetails(id, request, null);
    }

    @Transactional
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

    @Transactional
    public OrderResponse replaceDraftLines(UUID id, ReplaceOrderLinesRequest request, String bearer) {
        return replaceDraftLines(id, request, bearer, null);
    }

    @Transactional
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

    @Transactional
    public void deleteEmptyDraft(UUID id) {
        deleteEmptyDraft(id, null);
    }

    @Transactional
    public void deleteEmptyDraft(UUID id, OrderAccess access) {
        CorporateOrder order = requireOrder(id, access);
        try {
            order.requireDeletable();
            orderRepository.delete(order);
            orderRepository.flush();
        } catch (CorporateOrder.OrderStatusException exception) {
            throw statusConflict(exception.getMessage());
        } catch (CorporateOrder.NonEmptyOrderDeleteException exception) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "ORDER_DELETE_FORBIDDEN",
                    exception.getMessage());
        }
    }

    @Transactional
    public OrderResponse submit(UUID id, long expectedVersion, String bearer) {
        return submit(id, expectedVersion, bearer, null);
    }

    @Transactional
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
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ORDER_EMPTY", exception.getMessage());
        } catch (CorporateOrder.DeliveryTimeNotFutureException exception) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DELIVERY_TIME_NOT_FUTURE",
                    exception.getMessage());
        } catch (CorporateOrder.OrderStatusException exception) {
            throw statusConflict(exception.getMessage());
        }
    }

    @Transactional
    public OrderResponse confirm(UUID id, long expectedVersion) {
        return confirm(id, expectedVersion, null);
    }

    @Transactional
    public OrderResponse confirm(UUID id, long expectedVersion, OrderAccess access) {
        return executeStatusCommand(id, expectedVersion, access, order -> order.confirm(clock.instant(), changedBy(access)));
    }

    @Transactional
    public OrderResponse reject(UUID id, RejectOrderRequest request) {
        return reject(id, request, null);
    }

    @Transactional
    public OrderResponse reject(UUID id, RejectOrderRequest request, OrderAccess access) {
        return executeStatusCommand(
                id,
                request.expectedVersion(),
                access,
                order -> order.reject(request.reason(), clock.instant(), changedBy(access)));
    }

    @Transactional
    public OrderResponse cancel(UUID id, CancelOrderRequest request) {
        return cancel(id, request, null);
    }

    @Transactional
    public OrderResponse cancel(UUID id, CancelOrderRequest request, OrderAccess access) {
        return executeStatusCommand(
                id,
                request.expectedVersion(),
                access,
                order -> order.cancel(request.reason(), clock.instant(), changedBy(access)));
    }

    @Transactional
    public OrderResponse startCooking(UUID id, long expectedVersion) {
        return startCooking(id, expectedVersion, null);
    }

    @Transactional
    public OrderResponse startCooking(UUID id, long expectedVersion, OrderAccess access) {
        return executeStatusCommand(id, expectedVersion, access, order -> order.startCooking(clock.instant(), changedBy(access)));
    }

    @Transactional
    public OrderResponse markReady(UUID id, long expectedVersion) {
        return markReady(id, expectedVersion, null);
    }

    @Transactional
    public OrderResponse markReady(UUID id, long expectedVersion, OrderAccess access) {
        return executeStatusCommand(id, expectedVersion, access, order -> order.markReady(clock.instant(), changedBy(access)));
    }

    @Transactional
    public OrderResponse complete(UUID id, long expectedVersion) {
        return complete(id, expectedVersion, null);
    }

    @Transactional
    public OrderResponse complete(UUID id, long expectedVersion, OrderAccess access) {
        return executeStatusCommand(id, expectedVersion, access, order -> order.complete(clock.instant(), changedBy(access)));
    }

    public PageResponse<OrderStatusHistoryResponse> getHistory(UUID id, PageRequest pageRequest) {
        return getHistory(id, pageRequest, null);
    }

    public PageResponse<OrderStatusHistoryResponse> getHistory(UUID id, PageRequest pageRequest, OrderAccess access) {
        requireOrder(id, access);
        Page<OrderStatusHistory> history = historyRepository.findByOrder_Id(
                id,
                pageRequest.withSort(Sort.by(Sort.Order.asc("changedAt"), Sort.Order.asc("id"))));
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Организация обязательна");
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

    private ApiException orderNotFound(UUID id) {
        return new ApiException(
                HttpStatus.NOT_FOUND,
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
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "DUPLICATE_DISH",
                        "Блюдо " + line.dishId() + " повторяется в заказе");
            }
        }
    }

    private OrderResponse flushAndMap(CorporateOrder order) {
        try {
            orderRepository.flush();
            return toResponse(order);
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw versionConflict();
        } catch (CorporateOrder.OrderStatusException exception) {
            throw statusConflict(exception.getMessage());
        }
    }

    private ApiException versionConflict() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "ORDER_VERSION_CONFLICT",
                "Версия заказа устарела; перечитайте актуальное состояние");
    }

    private ApiException statusConflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "ORDER_STATUS_CONFLICT", message);
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
