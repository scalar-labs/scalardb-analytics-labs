-- Sample e-commerce source (PostgreSQL) for the agent-loop runbook.

CREATE TABLE customers (
    id            INTEGER PRIMARY KEY,
    email         TEXT    NOT NULL UNIQUE,
    name          TEXT    NOT NULL,
    registered_on DATE    NOT NULL
);

CREATE TABLE orders (
    id              INTEGER PRIMARY KEY,
    customer_id     INTEGER NOT NULL REFERENCES customers (id),
    ordered_on      DATE    NOT NULL,
    status          TEXT    NOT NULL,
    amount          BIGINT  NOT NULL,
    tax             BIGINT  NOT NULL
);

INSERT INTO customers (id, email, name, registered_on) VALUES
    (1, 'sato@example.com',      '佐藤', '2025-04-01'),
    (2, 'suzuki@example.com',    '鈴木', '2025-05-12'),
    (3, 'takahashi@example.com', '高橋', '2025-06-03'),
    (4, 'tanaka@example.com',    '田中', '2025-07-21'),
    (5, 'ito@example.com',       '伊藤', '2025-09-09'),
    (6, 'watanabe@example.com',  '渡辺', '2026-01-15'),
    (7, 'yamamoto@example.com',  '山本', '2026-03-30'),
    (8, 'nakamura@example.com',  '中村', '2026-06-18');

INSERT INTO orders (id, customer_id, ordered_on, status, amount, tax) VALUES
    (1001, 1, '2026-08-28', 'delivered',  4000,  400),
    (1002, 1, '2026-09-02', 'delivered', 12000, 1200),
    (1003, 2, '2026-09-05', 'shipped',    3500,  350),
    (1004, 3, '2026-09-07', 'cancelled',  8000,  800),
    (1005, 3, '2026-09-15', 'delivered',  6000,  600),
    (1006, 4, '2026-09-18', 'returned',   2500,  250),
    (1007, 5, '2026-09-20', 'delivered',  9000,  900),
    (1008, 6, '2026-09-25', 'shipped',    1500,  150),
    (1009, 2, '2026-09-30', 'delivered',  4500,  450),
    (1010, 7, '2026-10-01', 'delivered',  7000,  700),
    (1011, 1, '2026-10-03', 'shipped',    2000,  200),
    (1012, 8, '2026-10-06', 'cancelled',  3000,  300);

-- The CLI connects with a read-only role.
CREATE ROLE reader LOGIN PASSWORD 'shop-reader-password';
GRANT USAGE ON SCHEMA public TO reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO reader;
