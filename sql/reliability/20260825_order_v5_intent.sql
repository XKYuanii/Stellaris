-- 对现有订单分表补充 v5 Intent 关联字段；每个语句仅执行一次。
ALTER TABLE stellaris_order_0.d_order_0 ADD COLUMN intent_id VARCHAR(64) NULL AFTER identifier_id;
ALTER TABLE stellaris_order_0.d_order_1 ADD COLUMN intent_id VARCHAR(64) NULL AFTER identifier_id;
ALTER TABLE stellaris_order_0.d_order_2 ADD COLUMN intent_id VARCHAR(64) NULL AFTER identifier_id;
ALTER TABLE stellaris_order_0.d_order_3 ADD COLUMN intent_id VARCHAR(64) NULL AFTER identifier_id;
ALTER TABLE stellaris_order_1.d_order_0 ADD COLUMN intent_id VARCHAR(64) NULL AFTER identifier_id;
ALTER TABLE stellaris_order_1.d_order_1 ADD COLUMN intent_id VARCHAR(64) NULL AFTER identifier_id;
ALTER TABLE stellaris_order_1.d_order_2 ADD COLUMN intent_id VARCHAR(64) NULL AFTER identifier_id;
ALTER TABLE stellaris_order_1.d_order_3 ADD COLUMN intent_id VARCHAR(64) NULL AFTER identifier_id;
