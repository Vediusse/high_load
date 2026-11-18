package ru.itmo.highload.files.application.ports;
import java.util.UUID;
import ru.itmo.highload.files.application.OrderSnapshot;
public interface OrderAccess { OrderSnapshot require(UUID orderId,String bearer); }
