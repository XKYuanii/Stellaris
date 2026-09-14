-- Isolated benchmark fixture only. Refuses no paid-order check by itself;
-- run Cleanup-StellarisV5Benchmark.ps1 instead of executing this file directly.
SET @program_id = 900000;
USE stellaris_trade;
DELETE FROM d_reservation_transition_event WHERE program_id=@program_id;
DELETE FROM d_order_stream_failure WHERE program_id=@program_id;
DELETE FROM d_order_ticket_user WHERE program_id=@program_id;
DELETE FROM d_order_program WHERE program_id=@program_id;
DELETE FROM d_order WHERE program_id=@program_id;
DELETE FROM t_order_request WHERE program_id=@program_id;
DELETE FROM t_account_program_purchase WHERE program_id=@program_id;
DELETE FROM t_seat_inventory WHERE program_id=@program_id;
