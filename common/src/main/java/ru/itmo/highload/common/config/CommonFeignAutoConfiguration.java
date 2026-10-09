package ru.itmo.highload.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration")
@ConditionalOnClass(name = "org.springframework.cloud.openfeign.FeignClient")
public class CommonFeignAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(HttpMessageConverters.class)
    HttpMessageConverters feignMessageConverters(ObjectMapper mapper) {
        return new HttpMessageConverters(new MappingJackson2HttpMessageConverter(mapper));
    }
}
