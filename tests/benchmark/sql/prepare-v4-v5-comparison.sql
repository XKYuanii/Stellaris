-- Isolated V4/V5 comparison fixture.
-- IDs used inside legacy Lua stay below 2^53 to avoid cjson precision loss.
SET NAMES utf8mb4;
USE stellaris_program_0;

SET @program_id = 900100;
SET @category_id = 900101;
SET @seat_base = 9300000000000;

START TRANSACTION;

CREATE TEMPORARY TABLE tmp_stellaris_comparison_numbers (n INT PRIMARY KEY);
INSERT INTO tmp_stellaris_comparison_numbers(n)
SELECT ones.n + tens.n * 10 + hundreds.n * 100 + thousands.n * 1000
FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundreds
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) thousands;

INSERT INTO d_program_group_0
    (id, program_json, recent_show_time, create_time, edit_time, status)
VALUES
    (@program_id, '[{"programId":900100,"areaId":2,"areaIdName":"Beijing"}]',
     '2026-12-31 20:00:00', NOW(), NOW(), 1)
ON DUPLICATE KEY UPDATE
    program_json=VALUES(program_json), recent_show_time=VALUES(recent_show_time), edit_time=NOW(), status=1;

INSERT INTO d_program_0
    (id, program_group_id, prime, area_id, program_category_id, parent_program_category_id,
     title, actor, place, item_picture, pre_sell, detail,
     per_order_limit_purchase_count, per_account_limit_purchase_count,
     permit_refund, rel_name_ticket_entrance, permit_choose_seat,
     electronic_delivery_ticket, electronic_invoice, high_heat, program_status,
     issue_time, create_time, edit_time, status)
VALUES
    (@program_id, @program_id, 1, 2, 1, 1,
     'V4-V5 benchmark program', 'Benchmark Actor', 'Beijing Benchmark Theater', '', 0,
     'Isolated local V4/V5 benchmark; seat IDs stay below 2^53.',
     6, 6, 0, 0, 0, 1, 1, 0, 1,
     '2026-01-01 00:00:00', NOW(), NOW(), 1)
ON DUPLICATE KEY UPDATE
    title=VALUES(title), actor=VALUES(actor), place=VALUES(place), detail=VALUES(detail),
    per_order_limit_purchase_count=6, per_account_limit_purchase_count=6,
    permit_choose_seat=0, program_status=1, issue_time=VALUES(issue_time), edit_time=NOW(), status=1;

INSERT INTO d_program_show_time_0
    (id, program_id, show_time, show_day_time, show_week_time, area_id, create_time, edit_time, status)
VALUES
    (900102, @program_id, '2026-12-31 20:00:00', '2026-12-31 00:00:00', 'Thursday', 2, NOW(), NOW(), 1)
ON DUPLICATE KEY UPDATE
    show_time=VALUES(show_time), show_day_time=VALUES(show_day_time),
    show_week_time=VALUES(show_week_time), edit_time=NOW(), status=1;

INSERT INTO d_ticket_category_0
    (id, program_id, introduce, price, total_number, remain_number, create_time, edit_time, status)
VALUES
    (@category_id, @program_id, 'V4-V5 benchmark category A', 199, 10000, 10000, NOW(), NOW(), 1)
ON DUPLICATE KEY UPDATE
    introduce=VALUES(introduce), price=VALUES(price), total_number=10000,
    remain_number=10000, edit_time=NOW(), status=1;

DELETE FROM d_seat_0 WHERE program_id=@program_id;
INSERT INTO d_seat_0
    (id, program_id, ticket_category_id, row_code, col_code, seat_type, price,
     sell_status, reservation_id, seat_version, create_time, edit_time, status)
SELECT @seat_base+n, @program_id, @category_id,
       FLOOR(n/100)+1, MOD(n,100)+1, 1, 199,
       1, NULL, 0, NOW(), NOW(), 1
FROM tmp_stellaris_comparison_numbers
WHERE n < 10000;

DROP TEMPORARY TABLE tmp_stellaris_comparison_numbers;
COMMIT;
