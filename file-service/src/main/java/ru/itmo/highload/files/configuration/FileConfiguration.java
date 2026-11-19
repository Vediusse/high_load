package ru.itmo.highload.files.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import ru.itmo.highload.common.events.JdbcOutbox;
import ru.itmo.highload.files.adapters.out.config.RuntimeFilePolicy;
import ru.itmo.highload.files.adapters.out.order.OrderClient;
import ru.itmo.highload.files.adapters.out.order.OrderGateway;
import ru.itmo.highload.files.adapters.out.persistence.JdbcFiles;
import ru.itmo.highload.files.adapters.out.persistence.LegacyStorageMigration;
import ru.itmo.highload.files.adapters.out.storage.S3BinaryStorage;
import ru.itmo.highload.files.application.FileService;
import ru.itmo.highload.files.application.ports.BinaryStorage;
import ru.itmo.highload.files.application.ports.FilePolicy;
import ru.itmo.highload.files.application.ports.FileRecords;
import ru.itmo.highload.files.application.ports.OrderAccess;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class FileConfiguration {
    @Bean
    RuntimeFilePolicy filePolicy(
            Environment environment, @Value("${files.hard-max-bytes:10485760}") int hardMax) {
        return new RuntimeFilePolicy(environment, hardMax);
    }

    @Bean(destroyMethod = "close")
    S3Client s3Client(
            @Value("${files.s3.endpoint}") URI endpoint,
            @Value("${files.s3.region:us-east-1}") String region,
            @Value("${files.s3.access-key}") String access,
            @Value("${files.s3.secret-key}") String secret) {
        if (endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || !("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme())))
            throw new IllegalArgumentException("Invalid local S3 endpoint");
        return S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(region))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(access, secret)))
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(
                        UrlConnectionHttpClient.builder()
                                .connectionTimeout(Duration.ofSeconds(1))
                                .socketTimeout(Duration.ofSeconds(2)))
                .overrideConfiguration(
                        ClientOverrideConfiguration.builder()
                                .apiCallTimeout(Duration.ofSeconds(5))
                                .apiCallAttemptTimeout(Duration.ofSeconds(2))
                                .retryPolicy(RetryPolicy.builder().numRetries(1).build())
                                .build())
                .build();
    }

    @Bean
    BinaryStorage binaryStorage(
            S3Client client,
            @Value("${files.s3.bucket}") String bucket,
            @Value("${files.hard-max-bytes:10485760}") int hardMax) {
        return new S3BinaryStorage(client, bucket, hardMax);
    }

    @Bean
    ApplicationRunner initializeStorage(
            S3Client client,
            BinaryStorage storage,
            JdbcTemplate jdbc,
            @Value("${files.s3.bucket}") String bucket,
            @Value("${files.legacy-directory:}") String legacy,
            @Value("${files.hard-max-bytes:10485760}") int hardMax) {
        return args -> {
            client.headBucket(request -> request.bucket(bucket));
            if (!legacy.isBlank())
                new LegacyStorageMigration(jdbc, client, bucket, storage, Path.of(legacy), hardMax)
                        .run();
        };
    }

    @Bean
    JdbcOutbox eventOutbox(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new JdbcOutbox(jdbc, mapper);
    }

    @Bean
    FileRecords fileRecords(JdbcTemplate jdbc, JdbcOutbox outbox, PlatformTransactionManager tx) {
        return new JdbcFiles(jdbc, outbox, tx);
    }

    @Bean
    OrderAccess orderAccess(OrderClient client, ObjectMapper mapper) {
        return new OrderGateway(client, mapper);
    }

    @Bean
    FileService fileService(
            FileRecords records, BinaryStorage storage, OrderAccess orders, FilePolicy policy) {
        return new FileService(records, storage, orders, policy);
    }
}
