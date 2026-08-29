-- Read-only verification for program 900000. Run against MySQL 8.
SET @program_id = 900000;
SET @category_id = 900001;
SET @expected_seats = 10000;

SELECT 'mysql_available_seats' AS check_name,
       COUNT(*) AS actual,
       @expected_seats AS expected,
       IF(COUNT(*)=@expected_seats,'PASS','FAIL') AS result
FROM stellaris_program_0.d_seat_0
WHERE program_id=@program_id AND sell_status=1 AND reservation_id IS NULL;

SELECT 'ticket_category_remain' AS check_name,
       remain_number AS actual,
       @expected_seats AS expected,
       IF(remain_number=@expected_seats,'PASS','FAIL') AS result
FROM stellaris_program_0.d_ticket_category_0
WHERE program_id=@program_id AND id=@category_id;

SELECT order_status, COUNT(*) AS order_count
FROM (
  SELECT order_status FROM stellaris_order_0.d_order_0 WHERE program_id=@program_id
  UNION ALL SELECT order_status FROM stellaris_order_0.d_order_1 WHERE program_id=@program_id
  UNION ALL SELECT order_status FROM stellaris_order_0.d_order_2 WHERE program_id=@program_id
  UNION ALL SELECT order_status FROM stellaris_order_0.d_order_3 WHERE program_id=@program_id
  UNION ALL SELECT order_status FROM stellaris_order_1.d_order_0 WHERE program_id=@program_id
  UNION ALL SELECT order_status FROM stellaris_order_1.d_order_1 WHERE program_id=@program_id
  UNION ALL SELECT order_status FROM stellaris_order_1.d_order_2 WHERE program_id=@program_id
  UNION ALL SELECT order_status FROM stellaris_order_1.d_order_3 WHERE program_id=@program_id
) benchmark_orders
GROUP BY order_status ORDER BY order_status;

SELECT order_number, COUNT(*) AS copies
FROM (
  SELECT order_number FROM stellaris_order_0.d_order_0 WHERE program_id=@program_id
  UNION ALL SELECT order_number FROM stellaris_order_0.d_order_1 WHERE program_id=@program_id
  UNION ALL SELECT order_number FROM stellaris_order_0.d_order_2 WHERE program_id=@program_id
  UNION ALL SELECT order_number FROM stellaris_order_0.d_order_3 WHERE program_id=@program_id
  UNION ALL SELECT order_number FROM stellaris_order_1.d_order_0 WHERE program_id=@program_id
  UNION ALL SELECT order_number FROM stellaris_order_1.d_order_1 WHERE program_id=@program_id
  UNION ALL SELECT order_number FROM stellaris_order_1.d_order_2 WHERE program_id=@program_id
  UNION ALL SELECT order_number FROM stellaris_order_1.d_order_3 WHERE program_id=@program_id
) benchmark_orders
GROUP BY order_number HAVING COUNT(*)<>1;

SELECT reservation_id, COUNT(*) AS seat_count
FROM stellaris_program_0.d_seat_0
WHERE program_id=@program_id AND reservation_id IS NOT NULL
GROUP BY reservation_id ORDER BY reservation_id;
