-- The CRM source: customers, loaded into the crm database.
CREATE TABLE customers (
    customer_id INT UNSIGNED PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    region VARCHAR(10) NOT NULL,
    signed_up DATE NOT NULL,
    credit_limit DECIMAL(10, 2) NOT NULL,
    loyalty_points BIGINT
);

INSERT INTO customers VALUES
    (1, 'Aoki Trading', 'east', '2024-04-01', 50000.00, 1200),
    (2, 'Brightline', 'west', '2024-07-15', 25000.50, NULL),
    (3, 'Chiba Foods', 'east', '2025-01-10', 10000.00, 300),
    (4, 'Delta Retail', 'north', '2025-03-03', 75000.25, 8800),
    (5, 'Emi Studio', 'west', '2025-11-30', 5000.00, 0);
