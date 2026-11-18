package ru.itmo.highload.catering.identity.configuration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import ru.itmo.highload.catering.identity.application.UserService;
import ru.itmo.highload.catering.identity.application.ports.*;
import ru.itmo.highload.common.config.UseCaseTransactions;
@Configuration
public class IdentityConfiguration {
    @Bean PasswordHasher passwordHasher(PasswordEncoder encoder) {
        return new PasswordHasher() {
            public String encode(String password) { return encoder.encode(password); }
            public boolean matches(String password, String hash) { return encoder.matches(password, hash); }
        };
    }
    @Bean BootstrapLock bootstrapLock(JdbcTemplate jdbc) {
        return () -> jdbc.execute("select pg_advisory_xact_lock(73461920261009)");
    }
    @Bean UserService userService(UserStore users, RoleStore roles, PasswordHasher passwords,
            TokenIssuer tokens, OrganizationVerifier organizations, BootstrapLock lock,
            PlatformTransactionManager transactions) {
        return UseCaseTransactions.proxy(new UserService(users, roles, passwords, tokens, organizations, lock),
                transactions, "login", "get", "list", "current", "validateCurrent", "recipients");
    }
}
