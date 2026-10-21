-- Sample in-store point-of-sale source (MySQL) for the agent-loop runbook.

USE pos;

CREATE TABLE members (
    member_id   INT          PRIMARY KEY,
    email       VARCHAR(100) NOT NULL,
    member_rank CHAR(1)      NOT NULL,
    joined_on   DATE         NOT NULL
);

CREATE TABLE store_sales (
    sale_id         INT         PRIMARY KEY,
    member_id       INT         NULL,
    store_code      VARCHAR(10) NOT NULL,
    sold_on         DATE        NOT NULL,
    amount_excl_tax INT         NOT NULL,
    FOREIGN KEY (member_id) REFERENCES members (member_id)
);

-- Members register their email address at the store counter, so its letter case varies.
INSERT INTO members (member_id, email, member_rank, joined_on) VALUES
    (501, 'SATO@example.com',      'G', '2024-11-02'),
    (502, 'takahashi@example.com', 'S', '2025-02-14'),
    (503, 'Ito@Example.com',       'B', '2025-08-08'),
    (504, 'kobayashi@example.com', 'S', '2025-10-30'),
    (505, 'kato@example.com',      'B', '2026-02-01'),
    (506, 'watanabe@example.com',  'G', '2026-05-05');

-- A sale without a member card has no member_id.
INSERT INTO store_sales (sale_id, member_id, store_code, sold_on, amount_excl_tax) VALUES
    (9001, 501,  'S01', '2026-09-03', 5000),
    (9002, 502,  'S02', '2026-09-10', 2200),
    (9003, NULL, 'S01', '2026-09-11',  800),
    (9004, 504,  'S01', '2026-09-14', 3100),
    (9005, 506,  'S03', '2026-10-02', 4100),
    (9006, 503,  'S02', '2026-08-30', 1200),
    (9007, 505,  'S03', '2026-09-22',  900),
    (9008, 501,  'S02', '2026-09-27', 1800);

-- The CLI connects with a read-only user.
CREATE USER 'reader'@'%' IDENTIFIED BY 'pos-reader-password';
GRANT SELECT ON pos.* TO 'reader'@'%';
