-- 为已按 20260825_order_intent.sql 建表的环境补充订单关联字段。
-- 在每个实际 d_order_intent_N 分表执行一次；先检查 INFORMATION_SCHEMA，避免重复 ALTER。
ALTER TABLE d_order_intent_0 ADD COLUMN order_number BIGINT NULL AFTER program_id,
    ADD UNIQUE KEY uk_order_intent_order (program_id, order_number);
ALTER TABLE d_order_intent_1 ADD COLUMN order_number BIGINT NULL AFTER program_id,
    ADD UNIQUE KEY uk_order_intent_order (program_id, order_number);
