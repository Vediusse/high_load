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
