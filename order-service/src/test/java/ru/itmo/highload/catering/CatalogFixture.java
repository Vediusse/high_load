package ru.itmo.highload.catering;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only HTTP peer: order tests exercise the real Feign client and their own PostgreSQL. */
class CatalogFixture {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, Dish> dishes = new ConcurrentHashMap<>();
    private final HttpServer server;
    volatile int failureStatus;
    volatile long delayMillis;
    volatile boolean incomplete;
    volatile String lastTrace;
    final AtomicInteger requests = new AtomicInteger();
    final List<Integer> batchSizes = new java.util.concurrent.CopyOnWriteArrayList<>();
    CatalogFixture() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(task -> {
                Thread thread = new Thread(task); thread.setDaemon(true); return thread;
            }));
            server.createContext("/internal/v1/dishes/snapshots", exchange -> {
                requests.incrementAndGet();
                lastTrace = exchange.getRequestHeaders().getFirst("X-Trace-Id");
                var ids = mapper.readTree(exchange.getRequestBody()).get("ids");
                batchSizes.add(ids.size());
                int status = failureStatus == 0 ? 200 : failureStatus;
                Object body = Map.of("code", "INTERNAL_ERROR", "message", "unavailable", "fieldErrors", List.of(), "traceId", "fixture");
                List<Map<String, Object>> snapshots = new ArrayList<>();
                if (failureStatus == 0) {
                    for (var value : ids) {
                        Dish dish = dishes.get(UUID.fromString(value.asText()));
                        if (dish == null || !dish.active) {
                            status = dish == null ? 404 : 422;
                            body = Map.of("code", dish == null ? "RESOURCE_NOT_FOUND" : "DISH_INACTIVE", "message", "dish unavailable", "fieldErrors", List.of(), "traceId", "fixture");
                            break;
                        }
                        snapshots.add(Map.of("id", dish.id, "name", dish.name, "price", dish.currentPrice, "active", true));
                    }
                    if (status == 200) body = incomplete ? List.of() : snapshots;
                }
                try {
                    Thread.sleep(delayMillis);
                    byte[] bytes = mapper.writeValueAsBytes(body);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally { exchange.close(); }
            });
            server.start();
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    void reset() { dishes.clear(); failureStatus = 0; delayMillis = 0; incomplete = false; requests.set(0); batchSizes.clear(); }
    Dish dish(String name, String price) {
        Dish dish = new Dish(name, new BigDecimal(price)); dishes.put(dish.id, dish); return dish;
    }
    Dish get(UUID id) { return dishes.get(id); }
    static class Dish {
        private final UUID id = UUID.randomUUID();
        private String name;
        private BigDecimal currentPrice;
        private boolean active = true;
        Dish(String name, BigDecimal price) { this.name = name; currentPrice = price; }
        public UUID getId() { return id; }
        public String getName() { return name; }
        public BigDecimal getCurrentPrice() { return currentPrice; }
        void update(String name, String description, BigDecimal price, Set<?> categories) {
            this.name = name; currentPrice = price;
        }
        void deactivate() { active = false; }
    }
}
