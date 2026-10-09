package ru.itmo.highload.catering.organization.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

@Entity
@Table(name = "delivery_point")
public class DeliveryPoint {

    private static final String PHONE_PATTERN = "^\\+[1-9]\\d{7,14}$";

    @Id
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @NotBlank
    @Size(max = 200)
    @Column(nullable = false, length = 200)
    private String name;

    @NotBlank
    @Size(max = 500)
    @Column(nullable = false, length = 500)
    private String address;

    @NotBlank
    @Size(max = 200)
    @Column(name = "contact_name", nullable = false, length = 200)
    private String contactName;

    @NotBlank
    @Pattern(regexp = PHONE_PATTERN)
    @Column(name = "contact_phone", nullable = false, length = 32)
    private String contactPhone;

    @Column(nullable = false)
    private boolean active;

    protected DeliveryPoint() {
    }

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

    public UUID getId() {
        return id;
    }

    public Organization getOrganization() {
        return organization;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public String getContactName() {
        return contactName;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public boolean isActive() {
        return active;
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
