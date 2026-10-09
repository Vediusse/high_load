package ru.itmo.highload.catering.organization.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "organization")
public class Organization {

    private static final String PHONE_PATTERN = "^\\+[1-9]\\d{7,14}$";

    @Id
    private UUID id;

    @NotBlank
    @Size(max = 200)
    @Column(nullable = false, length = 200)
    private String name;

    @NotBlank
    @Pattern(regexp = PHONE_PATTERN)
    @Column(nullable = false, length = 32)
    private String phone;

    @Column(nullable = false)
    private boolean active;

    @Version
    @Column(nullable = false)
    private long version;

    @OneToMany(mappedBy = "organization", fetch = FetchType.LAZY)
    private Set<DeliveryPoint> deliveryPoints = new HashSet<>();

    protected Organization() {
    }

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

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getPhone() {
        return phone;
    }

    public boolean isActive() {
        return active;
    }

    public long getVersion() {
        return version;
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
