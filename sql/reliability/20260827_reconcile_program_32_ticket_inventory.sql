-- Reconcile the JJ20 demo program's ticket-category counters with its physical seat facts.
-- The original seed declared 500/1000 tickets per category but only created 40 seats per category.
-- Deriving the counters makes this migration idempotent even if some seats have already been sold.

START TRANSACTION;

UPDATE stellaris_program_0.d_ticket_category_0 AS category
JOIN (
    SELECT ticket_category_id,
           COUNT(*) AS total_number,
           SUM(CASE WHEN sell_status = 1 THEN 1 ELSE 0 END) AS remain_number
    FROM stellaris_program_0.d_seat_0
    WHERE program_id = 32
      AND status = 1
    GROUP BY ticket_category_id
) AS seat_fact ON seat_fact.ticket_category_id = category.id
SET category.total_number = seat_fact.total_number,
    category.remain_number = seat_fact.remain_number,
    category.edit_time = CURRENT_TIMESTAMP
WHERE category.program_id = 32
  AND category.status = 1;

COMMIT;

