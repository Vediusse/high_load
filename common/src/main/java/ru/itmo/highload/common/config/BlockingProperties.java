package ru.itmo.highload.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("catering.blocking")
public record BlockingProperties(@DefaultValue("8") int threads,
                                 @DefaultValue("100") int queueCapacity,
                                 @DefaultValue("blocking") String threadNamePrefix) {
    public BlockingProperties {
        if (threads < 1 || queueCapacity < 1 || threadNamePrefix.isBlank()) {
            throw new IllegalArgumentException("Blocking scheduler requires positive bounds and a thread prefix");
        }
    }
}
