-- The warehouse source: orders, loaded into the warehouse database. The rows are generated so the
-- tests can compute every expected value from the order ID.
CREATE SCHEMA sales;

CREATE TABLE sales.orders (
    order_id BIGINT PRIMARY KEY,
    customer_id INTEGER NOT NULL,
    ordered_at TIMESTAMP NOT NULL,
    ordered_on DATE NOT NULL,
    delivered_at TIMESTAMPTZ,
    pickup_time TIME NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    express BOOLEAN NOT NULL,
    note TEXT,
    receipt BYTEA
);

INSERT INTO sales.orders
SELECT g,
       g % 5 + 1,
       TIMESTAMP '2026-01-01 09:00:00' + g * INTERVAL '1 hour',
       DATE '2026-01-01' + (g / 24)::INTEGER,
       CASE WHEN g % 4 = 2 THEN TIMESTAMPTZ '2026-01-03 00:00:00+00' + g * INTERVAL '90 minutes' END,
       TIME '08:00:00' + (g % 20) * INTERVAL '15 minutes 30 seconds',
       g * 1.25,
       (ARRAY['placed', 'shipped', 'delivered', 'returned'])[g % 4 + 1],
       g % 2 = 0,
       CASE WHEN g % 7 = 0 THEN NULL ELSE 'order ' || g END,
       CASE WHEN g % 3 = 0 THEN decode(lpad(to_hex(g), 4, '0'), 'hex') END
FROM generate_series(1, 2000) AS g;

GRANT USAGE ON SCHEMA sales TO reader;
GRANT SELECT ON ALL TABLES IN SCHEMA sales TO reader;
