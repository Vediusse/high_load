package ru.itmo.highload.catering.organization.domain.model;

import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeliveryPoint {

    private static final String PHONE_PATTERN = "^\\+[1-9]\\d{7,14}$";

    private UUID id;

    private Organization organization;

    private String name;

    private String address;

    private String contactName;

    private String contactPhone;

    private boolean active;

    public DeliveryPoint(
            Organization organization,
            String name,
            String address,
            String contactName,
            String contactPhone) {
        if (organization == null) {
            throw new IllegalArgumentException("Организация обязательна");
        }
        this.id = UUID.randomUUID();
        this.organization = organization;
        this.active = true;
        update(name, address, contactName, contactPhone);
    }

    public void update(String name, String address, String contactName, String contactPhone) {
        this.name = requireText(name, "Название точки выдачи", 200);
        this.address = requireText(address, "Адрес", 500);
        this.contactName = requireText(contactName, "Контактное лицо", 200);
        this.contactPhone = requirePhone(contactPhone);
    }

    public void deactivate() {
        this.active = false;
    }

    private static String requireText(String value, String label, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new IllegalArgumentException(label + " не должно быть пустым и длиннее " + maxLength + " символов");
        }
        return value.trim();
    }

    private static String requirePhone(String value) {
        if (value == null || !value.matches(PHONE_PATTERN)) {
            throw new IllegalArgumentException("Телефон должен быть указан в формате E.164, например +79991234567");
        }
        return value;
    }
}
