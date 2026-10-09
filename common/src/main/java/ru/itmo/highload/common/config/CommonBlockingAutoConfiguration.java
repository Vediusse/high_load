package ru.itmo.highload.common.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import ru.itmo.highload.common.web.BlockingRequests;

@AutoConfiguration
@ConditionalOnProperty(prefix = "catering.blocking", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(BlockingProperties.class)
public class CommonBlockingAutoConfiguration {
    @Bean(destroyMethod = "dispose")
    @ConditionalOnMissingBean(name = "blockingRequestScheduler")
    Scheduler blockingRequestScheduler(BlockingProperties properties) {
        return Schedulers.newBoundedElastic(properties.threads(), properties.queueCapacity(), properties.threadNamePrefix());
    }

    @Bean
    @ConditionalOnMissingBean
    BlockingRequests blockingRequests(@Qualifier("blockingRequestScheduler") Scheduler scheduler) {
        return new BlockingRequests(scheduler);
    }
}
