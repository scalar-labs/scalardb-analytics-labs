-- Sample customers for the quickstart MySQL source.

USE crm;

CREATE TABLE customers (
    id     INT PRIMARY KEY,
    name   VARCHAR(50) NOT NULL,
    region VARCHAR(20) NOT NULL
);

INSERT INTO customers (id, name, region) VALUES
    (1, 'Alice', 'APAC'),
    (2, 'Bob',   'EMEA'),
    (3, 'Chen',  'APAC'),
    (4, 'Dana',  'AMER'),
    (5, 'Eve',   'EMEA');

-- The CLI connects with a read-only user.
CREATE USER 'reader'@'%' IDENTIFIED BY 'crm-reader-password';
GRANT SELECT ON crm.* TO 'reader'@'%';
