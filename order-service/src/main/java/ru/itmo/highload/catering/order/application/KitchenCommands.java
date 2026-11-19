package ru.itmo.highload.catering.order.application;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import ru.itmo.highload.catering.order.application.dto.in.KitchenCommand;
import ru.itmo.highload.catering.order.application.dto.out.OrderResponse;
import ru.itmo.highload.catering.order.application.ports.CommandReceipts;
import ru.itmo.highload.common.domain.BusinessFailure;

@RequiredArgsConstructor
public class KitchenCommands {
    private final CommandReceipts receipts;
    private final OrderService orders;

    public OrderResponse execute(UUID id, KitchenCommand command) {
        return execute(id, command, null);
    }

    public OrderResponse execute(UUID id, KitchenCommand command, OrderAccess access) {
        receipts.lock(command.commandId());
        var existing = receipts.find(command.commandId());
        if (existing.isPresent()) {
            var receipt = existing.get();
            if (!id.equals(receipt.orderId())
                    || !command.action().name().equals(receipt.action())
                    || !command.expectedStatus().name().equals(receipt.expectedStatus())
                    || command.expectedVersion() != receipt.expectedVersion())
                throw new BusinessFailure(
                        BusinessFailure.Kind.CONFLICT,
                        "COMMAND_ID_CONFLICT",
                        "Идентификатор команды уже использован с другими параметрами");
            return receipt.response();
        }
        if (command.expectedStatus() != command.action().expectedStatus())
            throw new BusinessFailure(
                    BusinessFailure.Kind.CONFLICT,
                    "ORDER_STATUS_CONFLICT",
                    "Ожидаемый статус не соответствует команде");
        OrderResponse response =
                switch (command.action()) {
                    case START_COOKING ->
                            orders.startCooking(id, command.expectedVersion(), access);
                    case MARK_READY -> orders.markReady(id, command.expectedVersion(), access);
                    case COMPLETE -> orders.complete(id, command.expectedVersion(), access);
                };
        receipts.save(id, command, response);
        return response;
    }
}
