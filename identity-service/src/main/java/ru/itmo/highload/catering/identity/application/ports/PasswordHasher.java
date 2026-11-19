package ru.itmo.highload.catering.identity.application.ports;

public interface PasswordHasher {
    String encode(String password);

    boolean matches(String password, String hash);
}
