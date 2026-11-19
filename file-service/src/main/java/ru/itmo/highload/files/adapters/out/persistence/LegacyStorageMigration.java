package ru.itmo.highload.files.adapters.out.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.itmo.highload.files.adapters.out.storage.S3BinaryStorage;
import ru.itmo.highload.files.application.ports.BinaryStorage;
import ru.itmo.highload.files.domain.FileContent;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

@RequiredArgsConstructor
public final class LegacyStorageMigration {
    private final JdbcTemplate jdbc;
    private final S3Client client;
    private final String bucket;
    private final BinaryStorage target;
    private final Path legacyDirectory;
    private final int hardMaxBytes;

    public long run() {
        String migration = "legacy-to-s3:" + bucket;
        var completed =
                jdbc.queryForList(
                        "select object_count from file_storage_migration where id=?",
                        Long.class,
                        migration);
        if (!completed.isEmpty()) return completed.getFirst();
        UUID cursor = null;
        long count = 0;
        while (true) {
            var batch =
                    jdbc.query(
                            "select * from file_document where (cast(? as uuid) is null or id > cast(? as uuid)) order by id limit 50",
                            JdbcFiles.FILE_ROW,
                            cursor,
                            cursor);
            if (batch.isEmpty()) break;
            for (var file : batch) {
                if (!exists(file.storageKey())) {
                    byte[] bytes = readLegacy(file.storageKey());
                    FileContent.verify(bytes, file);
                    target.write(file.storageKey(), bytes);
                }
                FileContent.verify(target.read(file.storageKey()), file);
                count++;
                cursor = file.id();
            }
        }
        jdbc.update(
                "insert into file_storage_migration(id,object_count) values (?,?) on conflict(id) do nothing",
                migration,
                count);
        return count;
    }

    private boolean exists(UUID key) {
        try {
            client.headObject(
                    request -> request.bucket(bucket).key(S3BinaryStorage.objectKey(key)));
            return true;
        } catch (S3Exception error) {
            if (error.statusCode() == 404) return false;
            throw new IllegalStateException("Cannot inspect local S3 during migration");
        }
    }

    private byte[] readLegacy(UUID key) {
        try (var input =
                Files.newInputStream(
                        legacyDirectory.resolve(key.toString()), LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(hardMaxBytes + 1);
            if (bytes.length > hardMaxBytes)
                throw new IllegalStateException("Legacy file exceeds transfer bound");
            return bytes;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read a referenced legacy file: " + key);
        }
    }
}
