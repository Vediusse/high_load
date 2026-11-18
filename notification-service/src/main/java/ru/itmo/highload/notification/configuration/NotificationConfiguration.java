package ru.itmo.highload.notification.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import ru.itmo.highload.notification.adapters.out.identity.IdentityClient;
import ru.itmo.highload.notification.adapters.out.identity.IdentityRecipients;
import ru.itmo.highload.notification.adapters.out.persistence.JdbcNotifications;
import ru.itmo.highload.notification.application.NotificationService;
import ru.itmo.highload.notification.application.ports.NotificationStore;
import ru.itmo.highload.notification.application.ports.RecipientDirectory;

@Configuration
public class NotificationConfiguration {
    @Bean
    JdbcNotifications notificationStore(JdbcTemplate jdbc, PlatformTransactionManager tx) {
        return new JdbcNotifications(jdbc, tx);
    }

    @Bean
    RecipientDirectory recipientDirectory(
            IdentityClient client, @Value("${identity.notifications.secret}") String credential) {
        return new IdentityRecipients(client, credential);
    }

    @Bean
    NotificationService notificationService(
            NotificationStore store, RecipientDirectory recipients) {
        return new NotificationService(store, recipients);
    }
}
