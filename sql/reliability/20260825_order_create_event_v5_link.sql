-- 为已部署可靠事件表的环境补齐 v5 Intent 关联字段。
-- 在 stellaris_program_0 与 stellaris_program_1 中分别执行；每个库的两个物理分表都必须升级。
ALTER TABLE d_order_create_event_0
    ADD COLUMN intent_id VARCHAR(64) NULL AFTER program_id,
    ADD COLUMN order_version INT NULL AFTER intent_id,
    ADD UNIQUE KEY uk_order_create_event_intent (program_id, intent_id);
ALTER TABLE d_order_create_event_1
    ADD COLUMN intent_id VARCHAR(64) NULL AFTER program_id,
    ADD COLUMN order_version INT NULL AFTER intent_id,
    ADD UNIQUE KEY uk_order_create_event_intent (program_id, intent_id);
