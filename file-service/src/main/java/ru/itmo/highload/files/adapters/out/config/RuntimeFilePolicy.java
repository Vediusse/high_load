package ru.itmo.highload.files.adapters.out.config;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import ru.itmo.highload.files.application.ports.FilePolicy;
import ru.itmo.highload.files.domain.FileLimits;

public final class RuntimeFilePolicy
        implements FilePolicy, ApplicationListener<EnvironmentChangeEvent> {
    private final Environment environment;
    private final int hardMaxBytes;
    private final AtomicReference<PolicyStatus> applied;

    public RuntimeFilePolicy(Environment environment, int hardMaxBytes) {
        this.environment = environment;
        this.hardMaxBytes = hardMaxBytes;
        this.applied = new AtomicReference<>(new PolicyStatus(read(), 1, Instant.now(), null));
    }

    @Override
    public FileLimits current() {
        return applied.get().limits();
    }

    @Override
    public PolicyStatus status() {
        return applied.get();
    }

    @Override
    public synchronized void onApplicationEvent(EnvironmentChangeEvent event) {
        if (event.getKeys().stream()
                .noneMatch(key ->
                        key.equals("files.max-bytes") || key.equals("files.allowed-mime-types")))
            return;
        PolicyStatus previous = applied.get();
        try {
            FileLimits next = read();
            applied.set(new PolicyStatus(next, previous.generation() + 1, Instant.now(), null));
        } catch (RuntimeException invalid) {
            applied.set(
                    new PolicyStatus(
                            previous.limits(),
                            previous.generation(),
                            previous.appliedAt(),
                            "INVALID_FILE_POLICY"));
        }
    }

    private FileLimits read() {
        int max = environment.getProperty("files.max-bytes", Integer.class, 10485760);
        if (max > hardMaxBytes)
            throw new IllegalArgumentException("File limit exceeds transfer bound");
        Set<String> types =
                Arrays.stream(
                                environment
                                        .getProperty(
                                                "files.allowed-mime-types",
                                                "application/pdf,image/png,image/jpeg")
                                        .split(","))
                        .map(String::strip)
                        .collect(Collectors.toUnmodifiableSet());
        return new FileLimits(max, types);
    }
}
