package ru.itmo.highload.catering.order.adapters.out.persistence;

import java.util.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.itmo.highload.catering.order.application.ports.CommandReceipts;
import ru.itmo.highload.catering.order.application.dto.in.KitchenCommand;
import ru.itmo.highload.catering.order.application.dto.out.OrderResponse;
@Repository @RequiredArgsConstructor
public class JdbcCommandReceipts implements CommandReceipts {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public void lock(UUID id) { jdbc.query("select pg_advisory_xact_lock(?)",rs -> {},id.getMostSignificantBits()^id.getLeastSignificantBits()); }
    public Optional<Receipt> find(UUID id) {
        var rows=jdbc.queryForList("select * from kitchen_command where command_id = ?",id);
        if(rows.isEmpty()) return Optional.empty();
        var row=rows.getFirst();
        try {
            return Optional.of(new Receipt((UUID)row.get("order_id"),row.get("action").toString(),row.get("expected_status").toString(),((Number)row.get("expected_version")).longValue(),mapper.readValue(row.get("response").toString(),OrderResponse.class)));
        } catch(JsonProcessingException error) { throw new IllegalStateException("Corrupt command receipt",error); }
    }
    public void save(UUID orderId,KitchenCommand command,OrderResponse response) {
        try {
            jdbc.update("insert into kitchen_command (command_id,order_id,action,expected_status,expected_version,response) values (?,?,?,?,?,cast(? as jsonb))",command.commandId(),orderId,command.action().name(),command.expectedStatus().name(),command.expectedVersion(),mapper.writeValueAsString(response));
        } catch(JsonProcessingException error) { throw new IllegalStateException("Cannot serialize command receipt",error); }
    }
}
