package ru.itmo.highload.files.application;
import java.util.UUID;
public record FileActor(UUID userId,String bearer) { }
