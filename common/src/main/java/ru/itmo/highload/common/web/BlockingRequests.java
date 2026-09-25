package ru.itmo.highload.common.web;

import java.util.concurrent.Callable;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

public class BlockingRequests {
    private final Scheduler scheduler;
    public BlockingRequests(Scheduler scheduler) {
        this.scheduler = scheduler;
    }
    public <T> Mono<T> call(Callable<T> work) {
        return Mono.deferContextual(context -> Mono.fromCallable(() -> {
            try (var ignored = MDC.putCloseable("traceId", context.getOrDefault("traceId", "internal"))) {
                return work.call();
            }
        }).subscribeOn(scheduler));
    }
}
