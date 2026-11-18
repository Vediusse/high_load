package ru.itmo.highload.catering.identity.application;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import ru.itmo.highload.catering.identity.application.dto.in.CreateUserRequest;
import ru.itmo.highload.catering.identity.application.dto.in.LoginRequest;
import ru.itmo.highload.catering.identity.application.dto.in.UpdateUserRolesRequest;
import ru.itmo.highload.catering.identity.application.dto.out.CurrentUserResponse;
import ru.itmo.highload.catering.identity.application.dto.out.TokenResponse;
import ru.itmo.highload.catering.identity.application.dto.out.UserResponse;
import ru.itmo.highload.catering.identity.domain.model.AppUser;
import ru.itmo.highload.catering.identity.domain.model.Role;
import ru.itmo.highload.catering.identity.domain.model.RoleCode;
import ru.itmo.highload.catering.identity.domain.model.UserStatus;


import ru.itmo.highload.common.dto.out.PageResponse;
import ru.itmo.highload.common.domain.BusinessFailure;
import ru.itmo.highload.common.domain.PageQuery;
import ru.itmo.highload.catering.identity.application.ports.*;
import ru.itmo.highload.catering.identity.domain.IdentityActor;
import ru.itmo.highload.catering.identity.domain.InvalidIdentityToken;


public class UserService {
    private final UserStore users;
    private final RoleStore roles;
    private final PasswordHasher passwords;
    private final TokenIssuer tokens;
    private final OrganizationVerifier organizations;
    private final BootstrapLock bootstrapLock;
    private final String dummyHash;

    public UserService(
            UserStore users,
            RoleStore roles,
            PasswordHasher passwords,
            TokenIssuer tokens,
            OrganizationVerifier organizations,
            BootstrapLock bootstrapLock) {
        this.users = users;
        this.roles = roles;
        this.passwords = passwords;
        this.tokens = tokens;
        this.organizations = organizations;
        this.bootstrapLock = bootstrapLock;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public TokenResponse login(LoginRequest request) {
        String login = request.login().strip().toLowerCase(Locale.ROOT);
        var user = users.findByLogin(login);
        boolean matches =
                request.password().getBytes(StandardCharsets.UTF_8).length <= 72
                        && passwords.matches(
                                request.password(),
                                user.map(AppUser::passwordHash).orElse(dummyHash));
        if (user.isEmpty() || !matches || user.get().getStatus() != UserStatus.ACTIVE) {
            throw new BusinessFailure(
                    BusinessFailure.Kind.UNAUTHENTICATED, "INVALID_CREDENTIALS", "Неверные учётные данные");
        }
        return tokens.issue(user.get());
    }

    public void validateCurrent(IdentityActor jwt) {
        requireCurrent(jwt);
    }

    private AppUser requireCurrent(IdentityActor jwt) {
        var user =
                users.findById(UUID.fromString(jwt.id().toString()))
                        .orElseThrow(() -> new InvalidIdentityToken());
        Number version = jwt.version();
        String organization = Objects.toString(jwt.organizationId(), null);
        if (user.getStatus() != UserStatus.ACTIVE
                || user.getVersion() != version.longValue()
                || !user.roleCodes().equals(jwt.roles())
                || !Objects.equals(
                        Objects.toString(user.effectiveOrganizationId(), null), organization)) {
            throw new InvalidIdentityToken();
        }
        return user;
    }

    public CurrentUserResponse current(IdentityActor jwt) {
        AppUser user = requireCurrent(jwt);
        return new CurrentUserResponse(user.getId(), user.getLogin(), user.getStatus(),
                user.roleCodes(), user.effectiveOrganizationId());
    }

    public UserResponse get(UUID id) {
        return toResponse(find(id));
    }

    public PageResponse<UserResponse> list(PageQuery query) {
        int page = query.page(), size = query.size();
        var result = users.findBy(query);
        return new PageResponse<>(
                result.getContent().stream().map(this::toResponse).toList(), page, size, result.hasNext());
    }

    public UserResponse create(CreateUserRequest request, IdentityActor actor) {
        String login = normalizeLogin(request.login());
        validatePassword(request.password());
        if (users.findByLogin(login).isPresent()) {
            throw loginConflict();
        }
        Set<Role> assigned = resolveRoles(request.roles());
        if (request.roles().contains(RoleCode.ORGANIZATION_REPRESENTATIVE)) {
            if (request.organizationId() == null) {
                throw invalid("Представителю обязательна организация");
            }
            verifyOrganization(request.organizationId(), actor);
        } else if (request.organizationId() != null) {
            throw invalid("Организация задаётся только представителю");
        }
        AppUser user =
                new AppUser(
                        login,
                        passwords.encode(request.password()),
                        assigned,
                        request.organizationId());
        return toResponse(users.saveAndFlush(user));
    }

    public UserResponse assignRoles(UUID id, UpdateUserRolesRequest request, IdentityActor actor) {
        AppUser user = find(id);
        Set<Role> assigned = resolveRoles(request.roles());
        if (request.roles().contains(RoleCode.ORGANIZATION_REPRESENTATIVE)) {
            if (user.getOrganizationId() == null) {
                throw new BusinessFailure(
                        BusinessFailure.Kind.REJECTED,
                        "ORGANIZATION_REQUIRED",
                        "Пользователь не привязан к организации");
            }
            verifyOrganization(user.getOrganizationId(), actor);
        }
        user.assignRoles(assigned);
        users.flush();
        return toResponse(user);
    }

    public UserResponse block(UUID id) {
        AppUser user = find(id);
        user.block();
        users.flush();
        return toResponse(user);
    }

    // Startup may compete with other JVMs; BCrypt/bootstrap has its own bounded deadline.

    public void bootstrap(String login, String password) {
        // Serialize first-run bootstrap across Identity replicas; lock ends with the transaction.
        bootstrapLock.acquire();
        if (users.count() != 0) {
            return;
        }
        if (login == null || login.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Set Identity bootstrap credentials for an empty database");
        }
        validatePassword(password);
        users.saveAndFlush(
                new AppUser(
                        normalizeLogin(login),
                        passwords.encode(password),
                        resolveRoles(Set.of(RoleCode.SUPERVISOR)),
                        null));
    }

    private UserResponse toResponse(AppUser user) {
        return new UserResponse(user.getId(), user.getLogin(), user.getStatus(),
                user.roleCodes(), user.effectiveOrganizationId());
    }

    private AppUser find(UUID id) {
        return users.findById(id)
                .orElseThrow(
                        () ->
                                new BusinessFailure(
                                        BusinessFailure.Kind.NOT_FOUND,
                                        "RESOURCE_NOT_FOUND",
                                        "Пользователь не найден"));
    }

    private Set<Role> resolveRoles(Collection<RoleCode> codes) {
        if (codes == null || codes.isEmpty() || codes.stream().anyMatch(Objects::isNull)) {
            throw invalid("Укажите роли");
        }
        if (new HashSet<>(codes).size() != codes.size()) {
            throw invalid("Роли не должны повторяться");
        }
        Set<Role> result = roles.findByCodeIn(Set.copyOf(codes));
        if (result.size() != codes.size()) {
            throw invalid("Неизвестная роль");
        }
        return result;
    }

    private void verifyOrganization(UUID id, IdentityActor actor) {
        if (actor.roles().contains(RoleCode.ORGANIZATION_REPRESENTATIVE)
                && !id.toString().equals(Objects.toString(actor.organizationId(), null))) {
            throw new BusinessFailure(
                    BusinessFailure.Kind.NOT_FOUND, "RESOURCE_NOT_FOUND", "Организация не найдена");
        }
        organizations.requireActive(id, actor.bearer());
    }

    private static String normalizeLogin(String login) {
        String result = login.strip().toLowerCase(Locale.ROOT);
        if (!result.matches("[a-z0-9._-]{3,64}")) {
            throw invalid("Логин: 3–64 символа A-Z, a-z, 0-9, '.', '_' или '-'");
        }
        return result;
    }

    private static void validatePassword(String password) {
        int length = password.getBytes(StandardCharsets.UTF_8).length;
        if (password.isBlank() || length < 12 || length > 72) {
            throw invalid("Пароль: 12–72 байта UTF-8");
        }
    }

    private static BusinessFailure invalid(String message) {
        return new BusinessFailure(BusinessFailure.Kind.VALIDATION, "VALIDATION_FAILED", message);
    }

    private static BusinessFailure loginConflict() {
        return new BusinessFailure(
                BusinessFailure.Kind.CONFLICT, "LOGIN_ALREADY_EXISTS", "Логин уже используется");
    }
}
