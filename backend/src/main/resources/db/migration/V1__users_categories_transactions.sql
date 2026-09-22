CREATE TABLE users (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    keycloak_id  TEXT NOT NULL UNIQUE,
    email        TEXT,
    display_name TEXT
);

CREATE TABLE categories (
    id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users (id),
    name    VARCHAR(100) NOT NULL,
    type    VARCHAR(7) NOT NULL CHECK (type IN ('INCOME', 'EXPENSE')),
    UNIQUE (user_id, id)
);

CREATE TABLE transactions (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    amount      NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    occurred_on DATE NOT NULL,
    note        VARCHAR(500),
    -- A transaction can only reference a category of the same user.
    FOREIGN KEY (user_id, category_id) REFERENCES categories (user_id, id)
);

CREATE INDEX ON transactions (user_id, occurred_on);
CREATE INDEX ON transactions (user_id, category_id);
