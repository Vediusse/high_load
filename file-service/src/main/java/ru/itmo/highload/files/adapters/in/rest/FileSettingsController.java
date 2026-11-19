package ru.itmo.highload.files.adapters.in.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import ru.itmo.highload.files.application.ports.FilePolicy;

@RestController
@Tag(name = "Runtime-конфигурация файлов")
@RequiredArgsConstructor
public class FileSettingsController {
    private final FilePolicy policy;

    @GetMapping("/api/v1/files/settings")
    @PreAuthorize("hasRole('SUPERVISOR')")
    @Operation(summary = "Показать фактически применённую политику загрузки без секретов")
    public Mono<FilePolicy.PolicyStatus> settings() {
        return Mono.fromSupplier(policy::status);
    }
}
