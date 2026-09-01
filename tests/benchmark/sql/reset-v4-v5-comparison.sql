-- Isolated comparison fixture only. Never execute in production.
SET NAMES utf8mb4;
SET @program_id = 900100;
SET @category_id = 900101;

START TRANSACTION;
UPDATE stellaris_program_0.d_seat_0
SET sell_status=1, reservation_id=NULL, seat_version=seat_version+1, edit_time=NOW(), status=1
WHERE program_id=@program_id;

UPDATE stellaris_program_0.d_ticket_category_0
SET remain_number=total_number, edit_time=NOW(), status=1
WHERE program_id=@program_id AND id=@category_id;

DELETE FROM stellaris_program_0.d_order_inventory_operation_0 WHERE program_id=@program_id;
DELETE FROM stellaris_program_0.d_order_inventory_operation_1 WHERE program_id=@program_id;
DELETE FROM stellaris_program_1.d_order_inventory_operation_0 WHERE program_id=@program_id;
DELETE FROM stellaris_program_1.d_order_inventory_operation_1 WHERE program_id=@program_id;

COMMIT;
