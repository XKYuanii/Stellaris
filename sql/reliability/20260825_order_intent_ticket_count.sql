-- 既有环境升级：为 v5 账户购票配额增加 Intent 事实列。
-- 历史数据应先根据 order_payload 回填真实票数；演示环境无历史 Intent 时可直接执行。
ALTER TABLE stellaris_program_0.d_order_intent_0 ADD COLUMN ticket_count INT NOT NULL DEFAULT 0 AFTER program_id;
ALTER TABLE stellaris_program_0.d_order_intent_1 ADD COLUMN ticket_count INT NOT NULL DEFAULT 0 AFTER program_id;
ALTER TABLE stellaris_program_1.d_order_intent_0 ADD COLUMN ticket_count INT NOT NULL DEFAULT 0 AFTER program_id;
ALTER TABLE stellaris_program_1.d_order_intent_1 ADD COLUMN ticket_count INT NOT NULL DEFAULT 0 AFTER program_id;

CREATE INDEX idx_order_intent_account_quota ON stellaris_program_0.d_order_intent_0(program_id, user_id, intent_status);
CREATE INDEX idx_order_intent_account_quota ON stellaris_program_0.d_order_intent_1(program_id, user_id, intent_status);
CREATE INDEX idx_order_intent_account_quota ON stellaris_program_1.d_order_intent_0(program_id, user_id, intent_status);
CREATE INDEX idx_order_intent_account_quota ON stellaris_program_1.d_order_intent_1(program_id, user_id, intent_status);
