package ru.itmo.highload.files.adapters.out.storage;

import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import ru.itmo.highload.files.application.ports.BinaryStorage;
import ru.itmo.highload.files.domain.FileFailure;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@RequiredArgsConstructor
public final class S3BinaryStorage implements BinaryStorage {
    private final S3Client client;
    private final String bucket;
    private final int hardMaxBytes;

    public static String objectKey(UUID key) {
        return "documents/" + key;
    }

    @Override
    public void write(UUID key, byte[] bytes) {
        try {
            client.putObject(
                    request ->
                            request.bucket(bucket)
                                    .key(objectKey(key))
                                    .ifNoneMatch("*")
                                    .contentType("application/octet-stream"),
                    RequestBody.fromBytes(bytes));
        } catch (SdkException error) {
            throw unavailable();
        }
    }

    @Override
    public byte[] read(UUID key) {
        try (var input = client.getObject(request -> request.bucket(bucket).key(objectKey(key)))) {
            byte[] bytes = input.readNBytes(hardMaxBytes + 1);
            if (bytes.length > hardMaxBytes)
                throw new FileFailure(
                        FileFailure.Kind.INTEGRITY,
                        "FILE_INTEGRITY_ERROR",
                        "Нарушен размер сохранённого файла");
            return bytes;
        } catch (NoSuchKeyException missing) {
            throw new FileFailure(
                    FileFailure.Kind.INTEGRITY,
                    "FILE_INTEGRITY_ERROR",
                    "Содержимое файла отсутствует в S3");
        } catch (IOException | SdkException error) {
            throw unavailable();
        }
    }

    @Override
    public void removeUncommitted(UUID key) {
        try {
            client.deleteObject(request -> request.bucket(bucket).key(objectKey(key)));
        } catch (SdkException error) {
            throw unavailable();
        }
    }

    private static FileFailure unavailable() {
        return new FileFailure(
                FileFailure.Kind.STORAGE,
                "FILE_STORAGE_UNAVAILABLE",
                "Локальное S3-хранилище временно недоступно");
    }
}
