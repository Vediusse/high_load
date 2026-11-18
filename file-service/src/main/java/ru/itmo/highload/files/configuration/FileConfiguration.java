package ru.itmo.highload.files.configuration;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.itmo.highload.common.events.JdbcOutbox;
import ru.itmo.highload.files.domain.FileLimits;
import ru.itmo.highload.files.application.*;
import ru.itmo.highload.files.application.ports.*;
import ru.itmo.highload.files.adapters.out.order.*;
import ru.itmo.highload.files.adapters.out.persistence.JdbcFiles;
import ru.itmo.highload.files.adapters.out.storage.LocalBinaryStorage;
@Configuration
public class FileConfiguration {
    @Bean FileLimits fileLimits(@Value("${files.max-bytes:10485760}") int max,
            @Value("${files.allowed-mime-types:application/pdf,image/png,image/jpeg}") Set<String> types) { return new FileLimits(max,types); }
    @Bean BinaryStorage binaryStorage(@Value("${files.storage-directory:/var/lib/catering-files}") String root,FileLimits limits) { return new LocalBinaryStorage(Path.of(root),limits.maxBytes()); }
    @Bean JdbcOutbox eventOutbox(JdbcTemplate jdbc,ObjectMapper mapper) { return new JdbcOutbox(jdbc,mapper); }
    @Bean FileRecords fileRecords(JdbcTemplate jdbc,JdbcOutbox outbox,PlatformTransactionManager tx) { return new JdbcFiles(jdbc,outbox,tx); }
    @Bean OrderAccess orderAccess(OrderClient client,ObjectMapper mapper) { return new OrderGateway(client,mapper); }
    @Bean FileService fileService(FileRecords records,BinaryStorage storage,OrderAccess orders,FileLimits limits) { return new FileService(records,storage,orders,limits); }
}
