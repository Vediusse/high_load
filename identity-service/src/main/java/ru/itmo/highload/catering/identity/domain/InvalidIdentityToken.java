package ru.itmo.highload.catering.identity.domain;
public final class InvalidIdentityToken extends RuntimeException {
    public InvalidIdentityToken() { super("Invalid token"); }
}
