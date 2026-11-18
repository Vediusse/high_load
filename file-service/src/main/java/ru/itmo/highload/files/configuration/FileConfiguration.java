package ru.itmo.highload.files.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import ru.itmo.highload.common.events.JdbcOutbox;
import ru.itmo.highload.files.adapters.out.order.OrderClient;
import ru.itmo.highload.files.adapters.out.order.OrderGateway;
import ru.itmo.highload.files.adapters.out.persistence.JdbcFiles;
import ru.itmo.highload.files.adapters.out.storage.LocalBinaryStorage;
import ru.itmo.highload.files.application.FileService;
import ru.itmo.highload.files.application.ports.BinaryStorage;
import ru.itmo.highload.files.application.ports.FileRecords;
import ru.itmo.highload.files.application.ports.OrderAccess;
import ru.itmo.highload.files.domain.FileLimits;

@Configuration
public class FileConfiguration {
    @Bean
    FileLimits fileLimits(
            @Value("${files.max-bytes:10485760}") int max,
            @Value("${files.allowed-mime-types:application/pdf,image/png,image/jpeg}")
                    Set<String> types) {
        return new FileLimits(max, types);
    }

    @Bean
    BinaryStorage binaryStorage(
            @Value("${files.storage-directory:/var/lib/catering-files}") String root,
            FileLimits limits) {
        return new LocalBinaryStorage(Path.of(root), limits.maxBytes());
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
            FileRecords records, BinaryStorage storage, OrderAccess orders, FileLimits limits) {
        return new FileService(records, storage, orders, limits);
    }
}
