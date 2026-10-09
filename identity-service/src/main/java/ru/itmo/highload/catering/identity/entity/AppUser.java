package ru.itmo.highload.catering.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "app_user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppUser {
    @Id
    private UUID id;

    @Column(nullable = false, length = 64)
    private String login;

    @Getter(AccessLevel.NONE)
    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserStatus status;

    private UUID organizationId;

    @Column(nullable = false)
    private Instant createdAt;

    @Version

    private long version;

    @Getter(AccessLevel.NONE)
    @ManyToMany
    @JoinTable(
            name = "user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    @BatchSize(size = 50)
    private Set<Role> roles = new HashSet<>();

    public AppUser(String login, String passwordHash, Set<Role> roles, UUID organizationId) {
        if (login == null
                || !login.matches("[a-z0-9._-]{3,64}")
                || passwordHash == null
                || !passwordHash.matches("\\$2[aby]\\$\\d{2}\\$.{53}")) {
            throw new IllegalArgumentException("Invalid user credentials");
        }
        this.id = UUID.randomUUID();
        this.login = login;
        this.passwordHash = passwordHash;
        this.organizationId = organizationId;
        this.status = UserStatus.ACTIVE;
        this.createdAt = Instant.now();
        assignRoles(roles);
    }

    public String passwordHash() {
        return passwordHash;
    }

    public Set<RoleCode> roleCodes() {
        return roles.stream().map(Role::getCode).collect(Collectors.toUnmodifiableSet());
    }

    public UUID effectiveOrganizationId() {
        return roleCodes().contains(RoleCode.ORGANIZATION_REPRESENTATIVE) ? organizationId : null;
    }

    public void assignRoles(Set<Role> assigned) {
        if (assigned == null || assigned.isEmpty() || assigned.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("At least one role is required");
        }
        if (assigned.stream()
                        .anyMatch(role -> role.getCode() == RoleCode.ORGANIZATION_REPRESENTATIVE)
                && organizationId == null) {
            throw new IllegalArgumentException("Representative requires organization");
        }
        if (!roles.equals(assigned)) {
            roles.clear();
            roles.addAll(assigned);
        }
    }

    public void block() {
        status = UserStatus.BLOCKED;
    }
}
