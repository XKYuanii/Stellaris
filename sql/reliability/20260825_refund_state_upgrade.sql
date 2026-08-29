-- 既有环境升级；新建环境已由 cloud/reliability 基线脚本直接创建这些列。
ALTER TABLE stellaris_pay_0.d_refund_intent_0 ADD COLUMN next_retry_time DATETIME NULL AFTER retry_count;
ALTER TABLE stellaris_pay_0.d_refund_intent_1 ADD COLUMN next_retry_time DATETIME NULL AFTER retry_count;
ALTER TABLE stellaris_pay_1.d_refund_intent_0 ADD COLUMN next_retry_time DATETIME NULL AFTER retry_count;
ALTER TABLE stellaris_pay_1.d_refund_intent_1 ADD COLUMN next_retry_time DATETIME NULL AFTER retry_count;

ALTER TABLE stellaris_pay_0.d_refund_bill_0 ADD COLUMN refund_no VARCHAR(64) NULL AFTER id, ADD UNIQUE KEY uk_refund_bill_no(refund_no);
ALTER TABLE stellaris_pay_0.d_refund_bill_1 ADD COLUMN refund_no VARCHAR(64) NULL AFTER id, ADD UNIQUE KEY uk_refund_bill_no(refund_no);
ALTER TABLE stellaris_pay_1.d_refund_bill_0 ADD COLUMN refund_no VARCHAR(64) NULL AFTER id, ADD UNIQUE KEY uk_refund_bill_no(refund_no);
ALTER TABLE stellaris_pay_1.d_refund_bill_1 ADD COLUMN refund_no VARCHAR(64) NULL AFTER id, ADD UNIQUE KEY uk_refund_bill_no(refund_no);

CREATE INDEX idx_refund_intent_retry_0 ON stellaris_pay_0.d_refund_intent_0(intent_status, next_retry_time);
CREATE INDEX idx_refund_intent_retry_1 ON stellaris_pay_0.d_refund_intent_1(intent_status, next_retry_time);
CREATE INDEX idx_refund_intent_retry_2 ON stellaris_pay_1.d_refund_intent_0(intent_status, next_retry_time);
CREATE INDEX idx_refund_intent_retry_3 ON stellaris_pay_1.d_refund_intent_1(intent_status, next_retry_time);
