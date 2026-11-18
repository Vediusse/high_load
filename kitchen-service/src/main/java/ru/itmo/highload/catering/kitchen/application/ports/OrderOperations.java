package ru.itmo.highload.catering.kitchen.application.ports;

import java.util.*;
import ru.itmo.highload.catering.kitchen.application.dto.in.KitchenCommand;
import ru.itmo.highload.catering.kitchen.application.dto.out.*;
import ru.itmo.highload.common.dto.out.PageResponse;
public interface OrderOperations {
    OrderResponse get(UUID id,String bearer);
    List<OrderState> states(Set<UUID> ids,String bearer);
    OrderResponse command(UUID id,KitchenCommand command,String bearer);
    PageResponse<OrderResponse> queue(int page,int size,String bearer);
}
