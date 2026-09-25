package ru.itmo.highload.catering.common.web;

import java.util.concurrent.Callable;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

@Component
public class BlockingRequests {
    private final Scheduler scheduler;
    public BlockingRequests(@Qualifier("orderBlockingScheduler") Scheduler scheduler) {
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
