package ru.itmo.highload.catering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import ru.itmo.highload.catering.organization.entity.Organization;
import ru.itmo.highload.catering.organization.repository.OrganizationRepository;

@SpringBootTest
class PersistenceConstraintsIT extends AbstractPostgresIT {

    @Autowired
    OrganizationRepository organizationRepository;



    @Test
    void databaseEnforcesOrganizationForeignKeysAndUniquePointNames() {
        Organization organization = organizationRepository.saveAndFlush(
                new Organization("Альфа", "+79991234567"));
        UUID firstPointId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO delivery_point (
                            id, organization_id, name, address, contact_name, contact_phone, active
                        ) VALUES (?, ?, ?, ?, ?, ?, true)
                        """,
                firstPointId,
                organization.getId(),
                "Офис",
                "Адрес",
                "Анна",
                "+79997654321");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO delivery_point (
                            id, organization_id, name, address, contact_name, contact_phone, active
                        ) VALUES (?, ?, ?, ?, ?, ?, true)
                        """,
                UUID.randomUUID(),
                organization.getId(),
                "Офис",
                "Другой адрес",
                "Мария",
                "+79991111111"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO delivery_point (
                            id, organization_id, name, address, contact_name, contact_phone, active
                        ) VALUES (?, ?, ?, ?, ?, ?, true)
                        """,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Чужой офис",
                "Адрес",
                "Иван",
                "+79992222222"))
                .isInstanceOf(DataIntegrityViolationException.class);

    }
}
