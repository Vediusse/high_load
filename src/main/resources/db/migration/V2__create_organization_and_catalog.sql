CREATE TABLE organization (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    phone VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_organization_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_organization_phone_format CHECK (phone ~ '^\+[1-9][0-9]{7,14}$')
);

CREATE TABLE delivery_point (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    name VARCHAR(200) NOT NULL,
    address VARCHAR(500) NOT NULL,
    contact_name VARCHAR(200) NOT NULL,
    contact_phone VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_delivery_point_organization
        FOREIGN KEY (organization_id) REFERENCES organization (id) ON DELETE RESTRICT,
    CONSTRAINT uq_delivery_point_organization_name UNIQUE (organization_id, name),
    CONSTRAINT ck_delivery_point_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_delivery_point_address_not_blank CHECK (btrim(address) <> ''),
    CONSTRAINT ck_delivery_point_contact_name_not_blank CHECK (btrim(contact_name) <> ''),
    CONSTRAINT ck_delivery_point_phone_format CHECK (contact_phone ~ '^\+[1-9][0-9]{7,14}$')
);

CREATE TABLE category (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    CONSTRAINT uq_category_name UNIQUE (name),
    CONSTRAINT ck_category_name_not_blank CHECK (btrim(name) <> '')
);

CREATE TABLE dish (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(2000) NOT NULL DEFAULT '',
    current_price NUMERIC(19, 2) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_dish_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_dish_current_price_positive CHECK (current_price > 0)
);

CREATE TABLE dish_category (
    dish_id UUID NOT NULL,
    category_id UUID NOT NULL,
    PRIMARY KEY (dish_id, category_id),
    CONSTRAINT fk_dish_category_dish
        FOREIGN KEY (dish_id) REFERENCES dish (id) ON DELETE RESTRICT,
    CONSTRAINT fk_dish_category_category
        FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE RESTRICT
);

CREATE INDEX idx_dish_active_id ON dish (active, id);
CREATE INDEX idx_dish_category_category_dish ON dish_category (category_id, dish_id);
