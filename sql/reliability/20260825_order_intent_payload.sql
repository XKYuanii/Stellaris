-- 对已创建的 v5 Intent 分表补充可恢复订单消息。每个语句仅执行一次。
ALTER TABLE stellaris_program_0.d_order_intent_0 ADD COLUMN order_payload LONGTEXT NULL AFTER seat_snapshot;
ALTER TABLE stellaris_program_0.d_order_intent_1 ADD COLUMN order_payload LONGTEXT NULL AFTER seat_snapshot;
ALTER TABLE stellaris_program_1.d_order_intent_0 ADD COLUMN order_payload LONGTEXT NULL AFTER seat_snapshot;
ALTER TABLE stellaris_program_1.d_order_intent_1 ADD COLUMN order_payload LONGTEXT NULL AFTER seat_snapshot;
