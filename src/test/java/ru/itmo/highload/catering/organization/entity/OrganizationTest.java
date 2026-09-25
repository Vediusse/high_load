package ru.itmo.highload.catering.organization.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OrganizationTest {

    @Test
    void managesOrganizationAndItsDeliveryPoint() {
        Organization organization = new Organization("  Альфа  ", "+79991234567");
        DeliveryPoint point = new DeliveryPoint(
                organization,
                "  Главный офис  ",
                "Невский проспект, 1",
                "Анна",
                "+79997654321");

        organization.addDeliveryPoint(point);
        organization.update("Альфа Плюс", "+79990000000");
        point.update("Офис", "Невский проспект, 2", "Мария", "+79991111111");
        point.deactivate();
        organization.deactivate();

        assertThat(organization.getId()).isNotNull();
        assertThat(organization.getName()).isEqualTo("Альфа Плюс");
        assertThat(organization.isActive()).isFalse();
        assertThat(point.getOrganization()).isSameAs(organization);
        assertThat(point.getName()).isEqualTo("Офис");
        assertThat(point.isActive()).isFalse();
    }

    @Test
    void rejectsInvalidOrganizationData() {
        assertThatThrownBy(() -> new Organization("", "+79991234567"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Organization("Альфа", "89991234567"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("E.164");
    }

    @Test
    void rejectsDeliveryPointFromAnotherOrganization() {
        Organization owner = new Organization("Альфа", "+79991234567");
        Organization other = new Organization("Бета", "+79991234568");
        DeliveryPoint point = new DeliveryPoint(owner, "Офис", "Адрес", "Анна", "+79991234569");

        assertThatThrownBy(() -> other.addDeliveryPoint(point))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("принадлежать организации");
    }
}
