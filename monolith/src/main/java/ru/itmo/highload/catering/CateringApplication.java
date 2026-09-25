package ru.itmo.highload.catering;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.cloud.openfeign.EnableFeignClients
@SpringBootApplication
public class CateringApplication {

    public static void main(String[] args) {
        SpringApplication.run(CateringApplication.class, args);
    }
}
