package ru.itmo.highload.catering;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class ProductionServiceApplication {
    public static void main(String[] args) { SpringApplication.run(ProductionServiceApplication.class, args); }
}
