package ru.itmo.highload.catering.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.highload.common.error.ApiException;
import ru.itmo.highload.catering.order.dto.OrderResponse;
import ru.itmo.highload.catering.order.dto.KitchenCommand;

@Service
@RequiredArgsConstructor
public class KitchenCommands {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final OrderService orders;

    @Transactional
    public OrderResponse execute(UUID orderId, KitchenCommand command) {
        // Lock even before the receipt exists; release only with the aggregate transaction.
        UUID id = command.commandId();
        jdbc.query("select pg_advisory_xact_lock(?)", rs -> {},
                id.getMostSignificantBits() ^ id.getLeastSignificantBits());
        var receipts = jdbc.queryForList("select * from kitchen_command where command_id = ?", id);
        if (!receipts.isEmpty()) {
            var receipt = receipts.getFirst();
            if (!orderId.equals(receipt.get("order_id"))
                    || !command.action().name().equals(receipt.get("action"))
                    || !command.expectedStatus().name().equals(receipt.get("expected_status"))
                    || command.expectedVersion() != ((Number) receipt.get("expected_version")).longValue()) {
                throw new ApiException(HttpStatus.CONFLICT, "COMMAND_ID_CONFLICT",
                        "Идентификатор команды уже использован с другими параметрами");
            }
            try {
                return mapper.readValue(receipt.get("response").toString(), OrderResponse.class);
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("Повреждён сохранённый результат команды", error);
            }
        }
        if (command.expectedStatus() != command.action().expectedStatus()) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_STATUS_CONFLICT",
                    "Ожидаемый статус не соответствует команде");
        }
        OrderResponse response = switch (command.action()) {
            case START_COOKING -> orders.startCooking(orderId, command.expectedVersion());
            case MARK_READY -> orders.markReady(orderId, command.expectedVersion());
            case COMPLETE -> orders.complete(orderId, command.expectedVersion());
        };
        try {
            jdbc.update("""
                    insert into kitchen_command
                        (command_id, order_id, action, expected_status, expected_version, response)
                    values (?, ?, ?, ?, ?, cast(? as jsonb))
                    """, id, orderId, command.action().name(), command.expectedStatus().name(),
                    command.expectedVersion(), mapper.writeValueAsString(response));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Не удалось сохранить результат команды", error);
        }
        return response;
    }
}
