package ru.itmo.highload.catering.catalog.application.ports;

import reactor.core.publisher.Mono;

public interface ReactiveUnitOfWork {
    <T> Mono<T> transactional(Mono<T> work);
}
