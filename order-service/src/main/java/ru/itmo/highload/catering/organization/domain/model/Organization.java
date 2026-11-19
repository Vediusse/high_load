package ru.itmo.highload.catering.organization.domain.model;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Organization {

    private static final String PHONE_PATTERN = "^\\+[1-9]\\d{7,14}$";

    private UUID id;

    private String name;

    private String phone;

    private boolean active;

    private long version;

    @Getter(AccessLevel.NONE)
    private Set<DeliveryPoint> deliveryPoints = new HashSet<>();

    public Organization(String name, String phone) {
        this.id = UUID.randomUUID();
        this.active = true;
        update(name, phone);
    }

    public void update(String name, String phone) {
        this.name = requireText(name, "Название организации", 200);
        this.phone = requirePhone(phone);
    }

    public void deactivate() {
        this.active = false;
    }

    public void addDeliveryPoint(DeliveryPoint deliveryPoint) {
        if (deliveryPoint == null || deliveryPoint.getOrganization() != this) {
            throw new IllegalArgumentException("Точка выдачи должна принадлежать организации");
        }
        deliveryPoints.add(deliveryPoint);
    }

    private static String requireText(String value, String label, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new IllegalArgumentException(
                    label + " не должно быть пустым и длиннее " + maxLength + " символов");
        }
        return value.trim();
    }

    private static String requirePhone(String value) {
        if (value == null || !value.matches(PHONE_PATTERN)) {
            throw new IllegalArgumentException(
                    "Телефон должен быть указан в формате E.164, например +79991234567");
        }
        return value;
    }
}
