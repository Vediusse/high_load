package ru.itmo.highload.common.config;

import java.util.Properties;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.TransactionProxyFactoryBean;

/** Wires transaction boundaries without adding Spring annotations to use cases. */
public final class UseCaseTransactions {
    private UseCaseTransactions() {}

    @SuppressWarnings("unchecked")
    public static <T> T proxy(
            T target, PlatformTransactionManager manager, String... readOnlyMethods) {
        Properties attributes = new Properties();
        attributes.setProperty("*", "PROPAGATION_REQUIRED");
        attributes.setProperty("bootstrap", "PROPAGATION_REQUIRED,timeout_30");
        for (String method : readOnlyMethods)
            attributes.setProperty(method, "PROPAGATION_REQUIRED,readOnly");
        TransactionProxyFactoryBean factory = new TransactionProxyFactoryBean();
        factory.setTarget(target);
        factory.setTransactionManager(manager);
        factory.setProxyTargetClass(true);
        factory.setTransactionAttributes(attributes);
        factory.afterPropertiesSet();
        return (T) factory.getObject();
    }
}
