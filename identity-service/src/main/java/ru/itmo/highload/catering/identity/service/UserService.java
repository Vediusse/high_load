package ru.itmo.highload.catering.identity.service;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.highload.catering.identity.dto.in.CreateUserRequest;
import ru.itmo.highload.catering.identity.dto.in.LoginRequest;
import ru.itmo.highload.catering.identity.dto.in.UpdateUserRolesRequest;
import ru.itmo.highload.catering.identity.dto.out.CurrentUserResponse;
import ru.itmo.highload.catering.identity.dto.out.TokenResponse;
import ru.itmo.highload.catering.identity.dto.out.UserResponse;
import ru.itmo.highload.catering.identity.entity.AppUser;
import ru.itmo.highload.catering.identity.entity.Role;
import ru.itmo.highload.catering.identity.entity.RoleCode;
import ru.itmo.highload.catering.identity.entity.UserStatus;
import ru.itmo.highload.catering.identity.repository.RoleRepository;
import ru.itmo.highload.catering.identity.repository.UserRepository;
import ru.itmo.highload.catering.identity.security.JwtTokenService;
import ru.itmo.highload.catering.identity.security.InternalTokenService;
import ru.itmo.highload.common.dto.out.PageResponse;
import ru.itmo.highload.common.error.ApiException;
import ru.itmo.highload.common.web.Pagination;

@Service
@Transactional(readOnly = true)
public class UserService {
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwords;
    private final JwtTokenService tokens;
    private final InternalTokenService internalTokens;
    private final OrganizationGateway organizations;
    private final JdbcTemplate jdbc;
    private final String dummyHash;

    public UserService(
            UserRepository users,
            RoleRepository roles,
            PasswordEncoder passwords,
            JwtTokenService tokens,
            OrganizationGateway organizations,
            JdbcTemplate jdbc,
            InternalTokenService internalTokens) {
        this.users = users;
        this.roles = roles;
        this.passwords = passwords;
        this.tokens = tokens;
        this.organizations = organizations;
        this.jdbc = jdbc;
        this.internalTokens = internalTokens;
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
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Неверные учётные данные");
        }
        return tokens.issue(user.get());
    }

    public void validateCurrent(Jwt jwt) {
        requireCurrent(jwt);
    }

    private AppUser requireCurrent(Jwt jwt) {
        var user =
                users.findById(UUID.fromString(jwt.getSubject()))
                        .orElseThrow(() -> new BadCredentialsException("Invalid token"));
        Number version = jwt.getClaim("ver");
        String organization = jwt.getClaimAsString("organizationId");
        if (user.getStatus() != UserStatus.ACTIVE
                || user.getVersion() != version.longValue()
                || !user.roleCodes().equals(JwtTokenService.roles(jwt))
                || !Objects.equals(
                        Objects.toString(user.effectiveOrganizationId(), null), organization)) {
            throw new BadCredentialsException("Invalid token");
        }
        return user;
    }

    public CurrentUserResponse current(Jwt jwt) {
        AppUser user = requireCurrent(jwt);
        return new CurrentUserResponse(user.getId(), user.getLogin(), user.getStatus(),
                user.roleCodes(), user.effectiveOrganizationId());
    }

    public UserResponse get(UUID id) {
        return toResponse(find(id));
    }

    public PageResponse<UserResponse> list(int page, int size) {
        var result = users.findBy(Pagination.pageRequest(page, size).withSort(Sort.by("id")));
        return new PageResponse<>(
                result.stream().map(this::toResponse).toList(), page, size, result.hasNext());
    }

    @Transactional
    public UserResponse create(CreateUserRequest request, Jwt actor) {
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

    @Transactional
    public UserResponse assignRoles(UUID id, UpdateUserRolesRequest request, Jwt actor) {
        AppUser user = find(id);
        Set<Role> assigned = resolveRoles(request.roles());
        if (request.roles().contains(RoleCode.ORGANIZATION_REPRESENTATIVE)) {
            if (user.getOrganizationId() == null) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "ORGANIZATION_REQUIRED",
                        "Пользователь не привязан к организации");
            }
            verifyOrganization(user.getOrganizationId(), actor);
        }
        user.assignRoles(assigned);
        users.flush();
        return toResponse(user);
    }

    @Transactional
    public UserResponse block(UUID id) {
        AppUser user = find(id);
        user.block();
        users.flush();
        return toResponse(user);
    }

    // Startup may compete with other JVMs; BCrypt/bootstrap has its own bounded deadline.
    @Transactional(timeout = 30)
    public void bootstrap(String login, String password) {
        // Serialize first-run bootstrap across Identity replicas; lock ends with the transaction.
        jdbc.execute("select pg_advisory_xact_lock(73461920261009)");
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
                                new ApiException(
                                        HttpStatus.NOT_FOUND,
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

    private void verifyOrganization(UUID id, Jwt actor) {
        if (JwtTokenService.roles(actor).contains(RoleCode.ORGANIZATION_REPRESENTATIVE)
                && !id.toString().equals(actor.getClaimAsString("organizationId"))) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Организация не найдена");
        }
        organizations.requireActive(id, internalTokens.downstreamBearer(actor));
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

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    private static ApiException loginConflict() {
        return new ApiException(
                HttpStatus.CONFLICT, "LOGIN_ALREADY_EXISTS", "Логин уже используется");
    }
}
