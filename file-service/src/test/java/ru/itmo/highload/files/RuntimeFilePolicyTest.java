package ru.itmo.highload.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.mock.env.MockEnvironment;
import ru.itmo.highload.files.adapters.out.config.RuntimeFilePolicy;

class RuntimeFilePolicyTest {
    @Test
    void appliesWholeValidSnapshotWithoutReconstructingService() {
        var environment = new MockEnvironment();
        var policy = new RuntimeFilePolicy(environment, 10485760);
        assertThat(policy.current().maxBytes()).isEqualTo(10485760);
        environment.setProperty("files.max-bytes", "2048");
        environment.setProperty("files.allowed-mime-types", "application/pdf");
        policy.onApplicationEvent(
                new EnvironmentChangeEvent(Set.of("files.max-bytes", "files.allowed-mime-types")));
        assertThat(policy.current().maxBytes()).isEqualTo(2048);
        assertThat(policy.current().allowedMimeTypes()).containsExactly("application/pdf");
        assertThat(policy.status().generation()).isEqualTo(2);
    }

    @Test
    void rejectsInvalidValuesAndKeepsLastValidSnapshot() {
        var environment = new MockEnvironment().withProperty("files.max-bytes", "2048");
        var policy = new RuntimeFilePolicy(environment, 10485760);
        for (String invalid : new String[] {"0", "-1", "99999999", "not-a-number"}) {
            environment.setProperty("files.max-bytes", invalid);
            policy.onApplicationEvent(new EnvironmentChangeEvent(Set.of("files.max-bytes")));
            assertThat(policy.current().maxBytes()).isEqualTo(2048);
            assertThat(policy.status().generation()).isEqualTo(1);
            assertThat(policy.status().lastRejection()).isEqualTo("INVALID_FILE_POLICY");
        }
        environment.setProperty("files.max-bytes", "4096");
        policy.onApplicationEvent(new EnvironmentChangeEvent(Set.of("files.max-bytes")));
        assertThat(policy.current().maxBytes()).isEqualTo(4096);
        assertThat(policy.status().lastRejection()).isNull();
    }

    @Test
    void unrelatedConfigurationDoesNotChangePolicy() {
        var policy = new RuntimeFilePolicy(new MockEnvironment(), 10485760);
        policy.onApplicationEvent(
                new EnvironmentChangeEvent(
                        Set.of("logging.level.root", "files.s3.endpoint", "files.hard-max-bytes")));
        assertThat(policy.status().generation()).isEqualTo(1);
    }
}
