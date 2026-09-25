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
import ru.itmo.highload.catering.catalog.dto.ActiveDishData;
import ru.itmo.highload.catering.catalog.service.CatalogGateway;
import ru.itmo.highload.common.dto.PageResponse;
import ru.itmo.highload.common.error.ApiException;
import ru.itmo.highload.catering.order.dto.CreateOrderRequest;
import ru.itmo.highload.catering.order.dto.CancelOrderRequest;
import ru.itmo.highload.catering.order.dto.OrderLineInput;
import ru.itmo.highload.catering.order.dto.OrderLineResponse;
import ru.itmo.highload.catering.order.dto.OrderPageResult;
import ru.itmo.highload.catering.order.dto.OrderResponse;
import ru.itmo.highload.catering.order.dto.OrderStatusHistoryResponse;
import ru.itmo.highload.catering.order.dto.RejectOrderRequest;
import ru.itmo.highload.catering.order.dto.ReplaceOrderLinesRequest;
import ru.itmo.highload.catering.order.dto.UpdateOrderDetailsRequest;
import ru.itmo.highload.catering.order.entity.CorporateOrder;
import ru.itmo.highload.catering.order.entity.OrderLine;
import ru.itmo.highload.catering.order.entity.OrderStatus;
import ru.itmo.highload.catering.order.entity.OrderStatusHistory;
import ru.itmo.highload.catering.order.repository.CorporateOrderRepository;
import ru.itmo.highload.catering.order.repository.OrderStatusHistoryRepository;
import ru.itmo.highload.catering.organization.service.OrganizationService;

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
        organizationService.requireActiveOrganizationAndPoint(
                request.organizationId(),
                request.deliveryPointId());
        CorporateOrder order = new CorporateOrder(
                request.organizationId(),
                request.deliveryPointId(),
                request.requestedDeliveryAt().toInstant(),
                request.comment(),
                clock.instant());
        return toResponse(orderRepository.saveAndFlush(order));
    }

    public OrderResponse getOrder(UUID id) {
        return toResponse(requireOrder(id));
    }

    public OrderPageResult listOrders(
            int page,
            int size,
            OrderStatus status,
            UUID organizationId) {
        if (organizationId != null) {
            organizationService.getOrganization(organizationId);
        }
        PageRequest pageRequest = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<CorporateOrder> orders = orderRepository.findPage(status, organizationId, pageRequest);
        PageResponse<OrderResponse> body = new PageResponse<>(
                orders.getContent().stream().map(this::toResponse).toList(),
                orders.getNumber(),
                orders.getSize(),
                orders.hasNext());
        return new OrderPageResult(body, orders.getTotalElements());
    }

    public PageResponse<OrderResponse> kitchenQueue(PageRequest request) {
        Page<CorporateOrder> page = orderRepository.findByStatusIn(
                Set.of(OrderStatus.CONFIRMED, OrderStatus.IN_COOKING, OrderStatus.READY),
                request.withSort(Sort.by("id")));
        return new PageResponse<>(page.getContent().stream().map(this::toResponse).toList(),
                page.getNumber(), page.getSize(), page.hasNext());
    }

    @Transactional
    public OrderResponse updateDraftDetails(UUID id, UpdateOrderDetailsRequest request) {
        CorporateOrder order = requireOrder(id);
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
    public OrderResponse replaceDraftLines(UUID id, ReplaceOrderLinesRequest request) {
        CorporateOrder order = requireOrder(id);
        requireExpectedVersion(order, request.expectedVersion());
        requireDraftStatus(order, "Состав заказа можно менять только в статусе DRAFT");
        ensureNoDuplicateDishes(request.lines());

        Set<UUID> dishIds = request.lines().stream()
                .map(OrderLineInput::dishId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, ActiveDishData> activeDishes = catalogGateway.getActiveDishPrices(dishIds);
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
        CorporateOrder order = requireOrder(id);
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
    public OrderResponse submit(UUID id, long expectedVersion) {
        CorporateOrder order = requireOrder(id);
        requireExpectedVersion(order, expectedVersion);
        try {
            organizationService.requireActiveOrganizationAndPoint(
                    order.getOrganizationId(),
                    order.getDeliveryPointId());
            Set<UUID> dishIds = order.getLines().stream()
                    .map(OrderLine::getDishId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<UUID, ActiveDishData> activeDishes = catalogGateway.getActiveDishPrices(dishIds);
            Map<UUID, CorporateOrder.DishSnapshot> snapshots = activeDishes.values().stream()
                    .collect(Collectors.toMap(
                            ActiveDishData::id,
                            dish -> new CorporateOrder.DishSnapshot(dish.name(), dish.currentPrice()),
                            (left, right) -> left,
                            LinkedHashMap::new));
            order.submit(snapshots, clock.instant());
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
        return executeStatusCommand(id, expectedVersion, order -> order.confirm(clock.instant()));
    }

    @Transactional
    public OrderResponse reject(UUID id, RejectOrderRequest request) {
        return executeStatusCommand(
                id,
                request.expectedVersion(),
                order -> order.reject(request.reason(), clock.instant()));
    }

    @Transactional
    public OrderResponse cancel(UUID id, CancelOrderRequest request) {
        return executeStatusCommand(
                id,
                request.expectedVersion(),
                order -> order.cancel(request.reason(), clock.instant()));
    }

    @Transactional
    public OrderResponse startCooking(UUID id, long expectedVersion) {
        return executeStatusCommand(id, expectedVersion, order -> order.startCooking(clock.instant()));
    }

    @Transactional
    public OrderResponse markReady(UUID id, long expectedVersion) {
        return executeStatusCommand(id, expectedVersion, order -> order.markReady(clock.instant()));
    }

    @Transactional
    public OrderResponse complete(UUID id, long expectedVersion) {
        return executeStatusCommand(id, expectedVersion, order -> order.complete(clock.instant()));
    }

    public PageResponse<OrderStatusHistoryResponse> getHistory(UUID id, PageRequest pageRequest) {
        if (!orderRepository.existsById(id)) {
            throw orderNotFound(id);
        }
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
        return orderRepository.findDetailedById(id)
                .orElseThrow(() -> orderNotFound(id));
    }

    private OrderResponse executeStatusCommand(
            UUID id,
            long expectedVersion,
            Consumer<CorporateOrder> command) {
        CorporateOrder order = requireOrder(id);
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
