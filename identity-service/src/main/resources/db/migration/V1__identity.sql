CREATE TABLE role (
    id SMALLINT PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE CHECK (code IN ('SUPERVISOR','ORGANIZATION_REPRESENTATIVE','CLIENT_MANAGER','KITCHEN_MANAGER'))
);
INSERT INTO role(id, code) VALUES (1,'SUPERVISOR'),(2,'ORGANIZATION_REPRESENTATIVE'),(3,'CLIENT_MANAGER'),(4,'KITCHEN_MANAGER');
CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    login VARCHAR(64) NOT NULL CONSTRAINT uk_app_user_login UNIQUE CHECK (login ~ '^[a-z0-9._-]{3,64}$'),
    password_hash VARCHAR(100) NOT NULL CHECK (password_hash ~ '^\$2[aby]\$[0-9]{2}\$.{53}$'),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','BLOCKED')),
    organization_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);
CREATE TABLE user_role (
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role_id SMALLINT NOT NULL REFERENCES role(id),
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX ix_user_role_role ON user_role(role_id);
CREATE INDEX ix_user_organization ON app_user(organization_id) WHERE organization_id IS NOT NULL;
