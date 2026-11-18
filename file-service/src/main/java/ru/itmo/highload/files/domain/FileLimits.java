package ru.itmo.highload.files.domain;
import java.util.Set;
public record FileLimits(int maxBytes,Set<String> allowedMimeTypes) {
    public FileLimits {
        allowedMimeTypes=Set.copyOf(allowedMimeTypes);
        if(maxBytes<1 || maxBytes==Integer.MAX_VALUE || allowedMimeTypes.isEmpty() || !Set.of("application/pdf","image/png","image/jpeg").containsAll(allowedMimeTypes))
            throw new IllegalArgumentException("Invalid file limits");
    }
}
