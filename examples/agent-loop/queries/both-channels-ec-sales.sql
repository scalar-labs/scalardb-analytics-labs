-- EC sales in September 2026 of the customers who bought both online and in store that month.
-- Each condition comes from confirmed knowledge: the EC sales Measure (aggregation rule, filter,
-- reference time), and the identifier correspondence of customer and member email addresses,
-- compared case-insensitively.
WITH september_ec AS (
    SELECT c.email, o.amount
    FROM shop.public.orders o
    JOIN shop.public.customers c ON c.id = o.customer_id
    WHERE o.status NOT IN ('cancelled', 'returned')
      AND o.ordered_on >= DATE '2026-09-01' AND o.ordered_on < DATE '2026-10-01'
),
september_store AS (
    SELECT DISTINCT lower(trim(m.email)) AS email
    FROM pos.pos.store_sales s
    JOIN pos.pos.members m ON m.member_id = s.member_id
    WHERE s.sold_on >= DATE '2026-09-01' AND s.sold_on < DATE '2026-10-01'
)
SELECT count(DISTINCT lower(trim(email))) AS customers, sum(amount) AS ec_sales
FROM september_ec
WHERE lower(trim(email)) IN (SELECT email FROM september_store)
