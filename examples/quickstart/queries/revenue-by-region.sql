-- Shipped revenue per customer region, joining the PostgreSQL and MySQL sources.
SELECT c.region,
       count(*)              AS orders,
       sum(o.amount)::BIGINT AS revenue
FROM warehouse.public.orders AS o
JOIN crm.crm.customers AS c ON o.customer_id = c.id
WHERE o.status = 'shipped'
GROUP BY c.region
ORDER BY revenue DESC
