package ru.itmo.highload.catering.identity.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ru.itmo.highload.catering.identity.client.OrganizationClient;
import ru.itmo.highload.catering.identity.client.dto.out.OrganizationResponse;
import ru.itmo.highload.common.dto.out.ApiError;
import ru.itmo.highload.common.error.ApiException;

@Service
@RequiredArgsConstructor
public class OrganizationGateway {
    private final OrganizationClient client;
    private final CircuitBreakerFactory<?, ?> breakers;
    private final ObjectMapper mapper;

    public void requireActive(UUID id, String bearer) {
        var organization =
                breakers.create("order")
                        .run(
                                () -> lookup(id, bearer),
                                error -> {
                                    if (error instanceof ApiException api) {
                                        throw api;
                                    }
                                    throw new ApiException(
                                            HttpStatus.SERVICE_UNAVAILABLE,
                                            "DEPENDENCY_UNAVAILABLE",
                                            "Сервис организаций временно недоступен");
                                });
        if (!organization.active()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "ORGANIZATION_INACTIVE",
                    "Организация неактивна");
        }
    }

    private OrganizationResponse lookup(UUID id, String bearer) {
        try {
            String trace = MDC.get("traceId");
            var organization =
                    client.get(id, bearer, trace == null ? UUID.randomUUID().toString() : trace);
            if (organization == null
                    || !id.equals(organization.id())
                    || organization.active() == null) {
                throw new IllegalStateException("Invalid organization response");
            }
            return organization;
        } catch (FeignException error) {
            // Preserve only a recognized API error; an HTML 404 is a dependency failure.
            if (Set.of(401, 403, 404).contains(error.status())) {
                try {
                    ApiError body = mapper.readValue(error.contentUTF8(), ApiError.class);
                    String expectedCode =
                            switch (error.status()) {
                                case 401 -> "AUTHENTICATION_REQUIRED";
                                case 403 -> "ACCESS_DENIED";
                                default -> "RESOURCE_NOT_FOUND";
                            };
                    if (body != null
                            && expectedCode.equals(body.code())
                            && body.message() != null
                            && body.fieldErrors() != null) {
                        throw new ApiException(
                                HttpStatus.valueOf(error.status()),
                                body.code(),
                                body.message(),
                                body.fieldErrors());
                    }
                } catch (JsonProcessingException invalidBody) {
                    throw error;
                }
            }
            throw error;
        }
    }
}
