package ru.itmo.highload.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.itmo.highload.files.domain.FileContent;
import ru.itmo.highload.files.domain.FileFailure;
import ru.itmo.highload.files.domain.FileLimits;

class FileContentTest {
    @Test
    void signaturesAllowOnlyConfiguredMimeAndChecksumDetectsChangedBytes() {
        var limits = new FileLimits(100, Set.of("application/pdf", "image/png", "image/jpeg"));
        var bodies =
                Map.of(
                        "application/pdf",
                        "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII),
                        "image/png",
                        new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10},
                        "image/jpeg",
                        new byte[] {(byte) 255, (byte) 216, (byte) 255, 1});
        bodies.forEach((mime, bytes) ->
                assertThat(FileContent.inspect(bytes, mime, limits).checksum()).hasSize(64));
        assertThatThrownBy(() ->
                        FileContent.inspect(
                                bodies.get("image/png"),
                                "image/png",
                                new FileLimits(100, Set.of("application/pdf"))))
                .isInstanceOf(FileFailure.class);
        assertThatThrownBy(() ->
                        FileContent.inspect(bodies.get("image/png"), "application/pdf", limits))
                .isInstanceOf(FileFailure.class);
        assertThatThrownBy(() -> new FileLimits(100, Set.of("image/svg+xml")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
