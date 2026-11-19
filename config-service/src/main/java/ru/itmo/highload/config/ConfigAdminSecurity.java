package ru.itmo.highload.config;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class ConfigAdminSecurity {
    @Bean
    SecurityFilterChain configSecurity(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .httpBasic(withDefaults())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(access ->
                        access.requestMatchers("/actuator/health", "/actuator/health/**")
                                .permitAll()
                                .requestMatchers("/actuator/busenv", "/actuator/busenv/**")
                                .denyAll()
                                .requestMatchers("/actuator/**")
                                .hasRole("CONFIG_ADMIN")
                                .requestMatchers(HttpMethod.GET, "/**")
                                .permitAll()
                                .anyRequest()
                                .denyAll())
                .build();
    }

    @Bean
    UserDetailsService configOperators(
            @Value("${CONFIG_ADMIN_USER:}") String username,
            @Value("${CONFIG_ADMIN_PASSWORD:}") String password,
            @Value("${spring.cloud.bus.enabled:false}") boolean enabled) {
        if (username.isBlank() || password.isBlank()) {
            if (enabled) throw new IllegalStateException("Set local config operator credentials");
            return new InMemoryUserDetailsManager();
        }
        String hash = new BCryptPasswordEncoder(12).encode(password);
        return new InMemoryUserDetailsManager(
                User.withUsername(username)
                        .password("{bcrypt}" + hash)
                        .roles("CONFIG_ADMIN")
                        .build());
    }
}
