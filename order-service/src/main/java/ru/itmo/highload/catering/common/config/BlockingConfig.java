package ru.itmo.highload.catering.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Configuration
public class BlockingConfig {
    @Bean(destroyMethod = "dispose")
    Scheduler orderBlockingScheduler(@Value("${order.blocking.threads:8}") int threads,
                                    @Value("${order.blocking.queue-capacity:100}") int queueCapacity) {
        return Schedulers.newBoundedElastic(threads, queueCapacity, "order-blocking");
    }
}
