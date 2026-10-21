-- Sample orders for the quickstart PostgreSQL source.

CREATE TABLE orders (
    id          INTEGER PRIMARY KEY,
    customer_id INTEGER NOT NULL,
    ordered_on  DATE    NOT NULL,
    status      TEXT    NOT NULL,
    amount      BIGINT  NOT NULL
);

INSERT INTO orders (id, customer_id, ordered_on, status, amount) VALUES
    (1001, 1, '2026-09-01', 'shipped',   1200),
    (1002, 2, '2026-09-03', 'shipped',    450),
    (1003, 1, '2026-09-10', 'cancelled',  300),
    (1004, 3, '2026-09-12', 'shipped',   2200),
    (1005, 4, '2026-09-20', 'pending',    800),
    (1006, 2, '2026-10-02', 'shipped',    950),
    (1007, 5, '2026-10-05', 'shipped',   1750),
    (1008, 3, '2026-10-08', 'pending',    600);

-- The CLI connects with a read-only role.
CREATE ROLE reader LOGIN PASSWORD 'warehouse-reader-password';
GRANT USAGE ON SCHEMA public TO reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO reader;
