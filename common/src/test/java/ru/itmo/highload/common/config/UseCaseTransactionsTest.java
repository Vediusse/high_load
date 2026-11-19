package ru.itmo.highload.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class UseCaseTransactionsTest {
    @Test
    void boundariesWrapPureUseCasesAndRollbackFailures() {
        var manager = new RecordingTransactions();
        var service = UseCaseTransactions.proxy(new UseCase(), manager, "get*");
        assertThat(service.getReadOnly()).isTrue();
        assertThat(service.writeReadOnly()).isFalse();
        assertThatThrownBy(service::fail).isInstanceOf(IllegalStateException.class);
        service.bootstrap();
        assertThat(manager.commits).isEqualTo(3);
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(manager.timeout).isEqualTo(30);
    }

    public static class UseCase {
        public boolean getReadOnly() {
            return TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        }

        public boolean writeReadOnly() {
            return TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        }

        public void fail() {
            throw new IllegalStateException("rollback");
        }

        public void bootstrap() {}
    }

    static class RecordingTransactions extends AbstractPlatformTransactionManager {
        int commits, rollbacks, timeout;

        protected Object doGetTransaction() {
            return new Object();
        }

        protected void doBegin(Object transaction, TransactionDefinition definition) {
            timeout = definition.getTimeout();
        }

        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}
