-- Proves, from the database alone, that the seat-holding race left no double booking.
-- Every query must return 0 (the last one just prints the summary).
--
-- Usage (compose database):
--   docker compose exec -T postgres psql -U ticketflow -d ticketflow -v event_id=<id> \
--       -f - < scripts/verify-no-double-booking.sql

\echo 'seats that appear in more than one active (PENDING/PAID) order -> must be 0'
select count(*) as double_booked_seats
from (
    select oi.event_seat_id
    from order_items oi
    join orders o on o.id = oi.order_id
    where o.event_id = :event_id and o.status in ('PENDING', 'PAID')
    group by oi.event_seat_id
    having count(*) > 1
) duplicated;

\echo 'HELD or SOLD seats whose order does not list them -> must be 0'
select count(*) as held_without_item
from event_seats es
where es.event_id = :event_id and es.status in ('HELD', 'SOLD')
  and not exists (select 1 from order_items oi where oi.order_id = es.order_id and oi.event_seat_id = es.id);

\echo 'items of active orders whose seat is not held by that same order -> must be 0'
select count(*) as item_without_held_seat
from order_items oi
join orders o on o.id = oi.order_id
join event_seats es on es.id = oi.event_seat_id
where o.event_id = :event_id and o.status = 'PENDING'
  and (es.status <> 'HELD' or es.order_id is distinct from o.id);

\echo 'orders left behind by a request that lost (PENDING without items) -> must be 0'
select count(*) as orders_without_items
from orders o
where o.event_id = :event_id and o.status = 'PENDING'
  and not exists (select 1 from order_items oi where oi.order_id = o.id);

\echo 'summary'
select (select count(*) from event_seats where event_id = :event_id and status = 'HELD') as held_seats,
       (select count(*) from event_seats where event_id = :event_id and status = 'AVAILABLE') as available_seats,
       (select count(*) from orders where event_id = :event_id and status = 'PENDING') as pending_orders;
