-- 每条 Intent 独立退避，避免固定 LIMIT 的永久失败记录饿死后续恢复任务。
ALTER TABLE stellaris_program_0.d_order_intent_0 ADD COLUMN next_retry_time DATETIME NULL AFTER retry_count;
ALTER TABLE stellaris_program_0.d_order_intent_1 ADD COLUMN next_retry_time DATETIME NULL AFTER retry_count;
ALTER TABLE stellaris_program_1.d_order_intent_0 ADD COLUMN next_retry_time DATETIME NULL AFTER retry_count;
ALTER TABLE stellaris_program_1.d_order_intent_1 ADD COLUMN next_retry_time DATETIME NULL AFTER retry_count;

CREATE INDEX idx_order_intent_next_retry ON stellaris_program_0.d_order_intent_0(intent_status, next_retry_time, id);
CREATE INDEX idx_order_intent_next_retry ON stellaris_program_0.d_order_intent_1(intent_status, next_retry_time, id);
CREATE INDEX idx_order_intent_next_retry ON stellaris_program_1.d_order_intent_0(intent_status, next_retry_time, id);
CREATE INDEX idx_order_intent_next_retry ON stellaris_program_1.d_order_intent_1(intent_status, next_retry_time, id);
