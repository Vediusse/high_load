package ru.itmo.highload.catering;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import ru.itmo.highload.catering.kitchen.client.dto.*;
import ru.itmo.highload.catering.kitchen.client.dto.OrderStatus;
import ru.itmo.highload.common.dto.PageResponse;

// Real HTTP partner for failure injection. Compose checks use the actual Order application.
final class OrderFixture {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final Map<UUID, OrderResponse> orders = new ConcurrentHashMap<>();
    final Map<UUID, OrderResponse> receipts = new ConcurrentHashMap<>();
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger transitions = new AtomicInteger();
    final HttpServer server;
    volatile String mode = "normal";
    volatile String trace;
    volatile int delay;

    OrderFixture() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool(r -> {var t=new Thread(r);t.setDaemon(true);return t;}));
            server.createContext("/internal/v1/orders", this::handle);
            server.start();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    String url() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
    void reset() { orders.clear();receipts.clear();calls.set(0);transitions.set(0);mode="normal";trace=null;delay=0; }
    OrderResponse add(OrderStatus status) {
        UUID id=UUID.randomUUID();
        var order=new OrderResponse(id,UUID.randomUUID(),UUID.randomUUID(),OffsetDateTime.parse("2099-01-01T12:00:00Z"),
                status,new BigDecimal("360.00"),null,3,OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                List.of(new OrderLineResponse(UUID.randomUUID(),UUID.randomUUID(),"Суп",2,new BigDecimal("180.00"),new BigDecimal("360.00"))));
        orders.put(id,order);return order;
    }
    OrderResponse state(OrderResponse before, OrderStatus status) {
        var next=new OrderResponse(before.id(),before.organizationId(),before.deliveryPointId(),before.requestedDeliveryAt(),
                status,before.totalAmount(),before.comment(),before.version()+1,before.createdAt(),before.lines());
        orders.put(next.id(),next);return next;
    }
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            calls.incrementAndGet(); trace=exchange.getRequestHeaders().getFirst("X-Trace-Id");
            int delayNow=delay;
            if (delayNow>0) try {Thread.sleep(delayNow);} catch (InterruptedException e) {Thread.currentThread().interrupt();}
            if (mode.equals("down")) {send(exchange,503,Map.of());return;}
            if (mode.equals("malformed")) {send(exchange,200,Map.of("id",UUID.randomUUID()));return;}
            if (mode.equals("bad-error")) {send(exchange,409,Map.of("code","UNKNOWN"));return;}
            String path=exchange.getRequestURI().getPath();
            if (path.endsWith("/states")) {
                var request = mapper.readValue(exchange.getRequestBody(), OrderStatesRequest.class);
                var states = request.ids().stream().map(orders::get)
                        .map(order -> new OrderState(order.id(), order.status(), order.version())).toList();
                send(exchange, 200, mode.equals("incomplete-states") ? List.of() : states);
                return;
            }
            if (path.endsWith("/kitchen")) {
                Map<String,String> query=new HashMap<>();
                for(String pair:exchange.getRequestURI().getQuery().split("&")){String[]parts=pair.split("=");query.put(parts[0],parts[1]);}
                int page=Integer.parseInt(query.get("page")),size=Integer.parseInt(query.get("size"));
                var active=orders.values().stream().filter(o -> Set.of(OrderStatus.CONFIRMED,OrderStatus.IN_COOKING,OrderStatus.READY).contains(o.status()))
                        .sorted(Comparator.comparing(o -> o.id().toString())).toList();
                send(exchange,200,new PageResponse<>(active.stream().skip((long)page*size).limit(size).toList(),page,size,(long)(page+1)*size<active.size()));
                return;
            }
            String[] parts=path.split("/"); UUID id=UUID.fromString(parts[4]);
            var current=orders.get(id);
            if(current==null){error(exchange,404,"RESOURCE_NOT_FOUND");return;}
            if(exchange.getRequestMethod().equals("GET")){send(exchange,200,current);return;}
            var command=mapper.readValue(exchange.getRequestBody(),KitchenCommand.class);
            OrderResponse response;
            synchronized(this) {
                response=receipts.get(command.commandId());
                if(response==null){
                    current=orders.get(id);
                    if(current.version()!=command.expectedVersion()){error(exchange,409,"ORDER_VERSION_CONFLICT");return;}
                    if(current.status()!=command.expectedStatus()){error(exchange,409,"ORDER_STATUS_CONFLICT");return;}
                    response=state(current,switch(command.action()){
                        case START_COOKING -> OrderStatus.IN_COOKING;case MARK_READY -> OrderStatus.READY;case COMPLETE -> OrderStatus.COMPLETED;});
                    receipts.put(command.commandId(),response); transitions.incrementAndGet();
                }
            }
            if(mode.equals("lost-reply")){mode="normal";exchange.sendResponseHeaders(200,1000);return;}
            send(exchange,200,response);
        }
    }
    private void error(HttpExchange exchange,int code,String name) throws IOException {
        send(exchange,code,Map.of("code",name,"message","Ошибка команды","fieldErrors",List.of(),"traceId",trace));
    }
    private void send(HttpExchange exchange,int status,Object body) throws IOException {
        byte[] bytes=mapper.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
    }
}
