-- V5 benchmark fixture. Reserved scope:
-- program=900000, category=900001, users=[910000000000000000,910000000000004999].
-- Run through Prepare-StellarisV5Benchmark.ps1 so active reservations are checked first.
SET NAMES utf8mb4;
USE stellaris_program_0;
SET @program_id = 900000;
SET @category_id = 900001;
SET @user_base = 910000000000000000;
SET @ticket_user_base = 920000000000000000;
SET @seat_base = 930000000000000000;

START TRANSACTION;

CREATE TEMPORARY TABLE tmp_stellaris_benchmark_numbers (n INT PRIMARY KEY);
INSERT INTO tmp_stellaris_benchmark_numbers(n)
SELECT ones.n + tens.n * 10 + hundreds.n * 100 + thousands.n * 1000
FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundreds
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) thousands;

INSERT INTO stellaris_program_0.d_program_group_0
    (id, program_json, recent_show_time, create_time, edit_time, status)
VALUES
    (@program_id, '[{"programId":900000,"areaId":2,"areaIdName":"北京"}]',
     '2026-12-31 20:00:00', NOW(), NOW(), 1)
ON DUPLICATE KEY UPDATE
    program_json = VALUES(program_json), recent_show_time = VALUES(recent_show_time),
    edit_time = NOW(), status = 1;

INSERT INTO stellaris_program_0.d_program_0
    (id, program_group_id, prime, area_id, program_category_id, parent_program_category_id,
     title, actor, place, item_picture, pre_sell, detail,
     per_order_limit_purchase_count, per_account_limit_purchase_count,
     permit_refund, rel_name_ticket_entrance, permit_choose_seat,
     electronic_delivery_ticket, electronic_invoice, high_heat, program_status,
     issue_time, create_time, edit_time, status)
VALUES
    (@program_id, @program_id, 1, 2, 1, 1,
     'V5并发压测专用节目', '星演压测演员', '北京星演测试剧场', '', 0,
     '仅供本地 benchmark profile 使用，不参与演示业务。',
     6, 6, 0, 0, 0, 1, 1, 0, 1,
     '2026-01-01 00:00:00', NOW(), NOW(), 1)
ON DUPLICATE KEY UPDATE
    title = VALUES(title), actor = VALUES(actor), place = VALUES(place), detail = VALUES(detail),
    per_order_limit_purchase_count = 6, per_account_limit_purchase_count = 6,
    permit_choose_seat = 0, program_status = 1, issue_time = VALUES(issue_time), edit_time = NOW(), status = 1;

INSERT INTO stellaris_program_0.d_program_show_time_0
    (id, program_id, show_time, show_day_time, show_week_time, area_id, create_time, edit_time, status)
VALUES
    (900002, @program_id, '2026-12-31 20:00:00', '2026-12-31 00:00:00', '周四', 2, NOW(), NOW(), 1)
ON DUPLICATE KEY UPDATE
    show_time = VALUES(show_time), show_day_time = VALUES(show_day_time),
    show_week_time = VALUES(show_week_time), edit_time = NOW(), status = 1;

INSERT INTO stellaris_program_0.d_ticket_category_0
    (id, program_id, introduce, price, total_number, remain_number, create_time, edit_time, status)
VALUES
    (@category_id, @program_id, '压测票档A', 199, 10000, 10000, NOW(), NOW(), 1)
ON DUPLICATE KEY UPDATE
    introduce = VALUES(introduce), price = VALUES(price), total_number = 10000,
    remain_number = 10000, edit_time = NOW(), status = 1;

DELETE FROM stellaris_program_0.d_seat_0 WHERE program_id = @program_id;
INSERT INTO stellaris_program_0.d_seat_0
    (id, program_id, ticket_category_id, row_code, col_code, seat_type, price,
     sell_status, reservation_id, seat_version, create_time, edit_time, status)
SELECT @seat_base + n, @program_id, @category_id,
       FLOOR(n / 100) + 1, MOD(n, 100) + 1, 1, 199,
       1, NULL, 0, NOW(), NOW(), 1
FROM tmp_stellaris_benchmark_numbers
WHERE n < 10000;

-- user_id % 4 routes as: 0=>db0/table0, 1=>db1/table0, 2=>db0/table1, 3=>db1/table1.
INSERT INTO stellaris_user_0.d_user_0
    (id, name, rel_name, mobile, gender, password, email_status, email,
     rel_authentication_status, id_number, address, create_time, edit_time, status)
SELECT @user_base + n, CONCAT('v5_test_', LPAD(n + 1, 6, '0')),
       CONCAT('压测用户', LPAD(n + 1, 6, '0')), CONCAT('1399', LPAD(n, 7, '0')),
       1, NULL, 0, NULL, 1, CONCAT('990000', LPAD(n, 12, '0')), '星演压测地址', NOW(), NOW(), 1
FROM tmp_stellaris_benchmark_numbers WHERE n < 5000 AND MOD(@user_base + n, 4) = 0
ON DUPLICATE KEY UPDATE name = VALUES(name), rel_name = VALUES(rel_name), edit_time = NOW(), status = 1;

INSERT INTO stellaris_user_1.d_user_0
    (id, name, rel_name, mobile, gender, password, email_status, email,
     rel_authentication_status, id_number, address, create_time, edit_time, status)
SELECT @user_base + n, CONCAT('v5_test_', LPAD(n + 1, 6, '0')),
       CONCAT('压测用户', LPAD(n + 1, 6, '0')), CONCAT('1399', LPAD(n, 7, '0')),
       1, NULL, 0, NULL, 1, CONCAT('990000', LPAD(n, 12, '0')), '星演压测地址', NOW(), NOW(), 1
FROM tmp_stellaris_benchmark_numbers WHERE n < 5000 AND MOD(@user_base + n, 4) = 1
ON DUPLICATE KEY UPDATE name = VALUES(name), rel_name = VALUES(rel_name), edit_time = NOW(), status = 1;

INSERT INTO stellaris_user_0.d_user_1
    (id, name, rel_name, mobile, gender, password, email_status, email,
     rel_authentication_status, id_number, address, create_time, edit_time, status)
SELECT @user_base + n, CONCAT('v5_test_', LPAD(n + 1, 6, '0')),
       CONCAT('压测用户', LPAD(n + 1, 6, '0')), CONCAT('1399', LPAD(n, 7, '0')),
       1, NULL, 0, NULL, 1, CONCAT('990000', LPAD(n, 12, '0')), '星演压测地址', NOW(), NOW(), 1
FROM tmp_stellaris_benchmark_numbers WHERE n < 5000 AND MOD(@user_base + n, 4) = 2
ON DUPLICATE KEY UPDATE name = VALUES(name), rel_name = VALUES(rel_name), edit_time = NOW(), status = 1;

INSERT INTO stellaris_user_1.d_user_1
    (id, name, rel_name, mobile, gender, password, email_status, email,
     rel_authentication_status, id_number, address, create_time, edit_time, status)
SELECT @user_base + n, CONCAT('v5_test_', LPAD(n + 1, 6, '0')),
       CONCAT('压测用户', LPAD(n + 1, 6, '0')), CONCAT('1399', LPAD(n, 7, '0')),
       1, NULL, 0, NULL, 1, CONCAT('990000', LPAD(n, 12, '0')), '星演压测地址', NOW(), NOW(), 1
FROM tmp_stellaris_benchmark_numbers WHERE n < 5000 AND MOD(@user_base + n, 4) = 3
ON DUPLICATE KEY UPDATE name = VALUES(name), rel_name = VALUES(rel_name), edit_time = NOW(), status = 1;

INSERT INTO stellaris_user_0.d_ticket_user_0
    (id, user_id, rel_name, id_type, id_number, create_time, edit_time, status)
SELECT @ticket_user_base + n, @user_base + n, CONCAT('观演人', LPAD(n + 1, 6, '0')),
       1, CONCAT('980000', LPAD(n, 12, '0')), NOW(), NOW(), 1
FROM tmp_stellaris_benchmark_numbers WHERE n < 5000 AND MOD(@user_base + n, 4) = 0
ON DUPLICATE KEY UPDATE rel_name = VALUES(rel_name), id_number = VALUES(id_number), edit_time = NOW(), status = 1;

INSERT INTO stellaris_user_1.d_ticket_user_0
    (id, user_id, rel_name, id_type, id_number, create_time, edit_time, status)
SELECT @ticket_user_base + n, @user_base + n, CONCAT('观演人', LPAD(n + 1, 6, '0')),
       1, CONCAT('980000', LPAD(n, 12, '0')), NOW(), NOW(), 1
FROM tmp_stellaris_benchmark_numbers WHERE n < 5000 AND MOD(@user_base + n, 4) = 1
ON DUPLICATE KEY UPDATE rel_name = VALUES(rel_name), id_number = VALUES(id_number), edit_time = NOW(), status = 1;

INSERT INTO stellaris_user_0.d_ticket_user_1
    (id, user_id, rel_name, id_type, id_number, create_time, edit_time, status)
SELECT @ticket_user_base + n, @user_base + n, CONCAT('观演人', LPAD(n + 1, 6, '0')),
       1, CONCAT('980000', LPAD(n, 12, '0')), NOW(), NOW(), 1
FROM tmp_stellaris_benchmark_numbers WHERE n < 5000 AND MOD(@user_base + n, 4) = 2
ON DUPLICATE KEY UPDATE rel_name = VALUES(rel_name), id_number = VALUES(id_number), edit_time = NOW(), status = 1;

INSERT INTO stellaris_user_1.d_ticket_user_1
    (id, user_id, rel_name, id_type, id_number, create_time, edit_time, status)
SELECT @ticket_user_base + n, @user_base + n, CONCAT('观演人', LPAD(n + 1, 6, '0')),
       1, CONCAT('980000', LPAD(n, 12, '0')), NOW(), NOW(), 1
FROM tmp_stellaris_benchmark_numbers WHERE n < 5000 AND MOD(@user_base + n, 4) = 3
ON DUPLICATE KEY UPDATE rel_name = VALUES(rel_name), id_number = VALUES(id_number), edit_time = NOW(), status = 1;

DROP TEMPORARY TABLE tmp_stellaris_benchmark_numbers;
COMMIT;
