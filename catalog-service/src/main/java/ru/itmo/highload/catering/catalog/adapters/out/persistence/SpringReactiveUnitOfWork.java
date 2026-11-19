package ru.itmo.highload.catering.catalog.adapters.out.persistence;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.catalog.application.ports.ReactiveUnitOfWork;
import ru.itmo.highload.catering.catalog.domain.DataConflict;

public class SpringReactiveUnitOfWork implements ReactiveUnitOfWork {
    private final TransactionalOperator transaction;

    public SpringReactiveUnitOfWork(ReactiveTransactionManager manager) {
        transaction = TransactionalOperator.create(manager);
    }

    public <T> Mono<T> transactional(Mono<T> work) {
        return transaction
                .transactional(work)
                .onErrorMap(DataIntegrityViolationException.class, DataConflict::new);
    }
}
