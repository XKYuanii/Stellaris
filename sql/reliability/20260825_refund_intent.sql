-- 退款 Intent：必须在调用支付渠道前持久化，refund_no 作为稳定幂等号。
USE stellaris_pay_0;
CREATE TABLE IF NOT EXISTS d_refund_intent_0 (
 id BIGINT NOT NULL, refund_no VARCHAR(64) NOT NULL, out_order_no VARCHAR(64) NOT NULL, pay_bill_id BIGINT NOT NULL,
 amount DECIMAL(18,2) NOT NULL, channel VARCHAR(32) NOT NULL, reason VARCHAR(500) NULL, intent_status VARCHAR(32) NOT NULL, retry_count INT NOT NULL DEFAULT 0,
 next_retry_time DATETIME NULL, last_error VARCHAR(1000) NULL, create_time DATETIME NOT NULL, edit_time DATETIME NOT NULL, status TINYINT NOT NULL DEFAULT 1,
 PRIMARY KEY(id), UNIQUE KEY uk_refund_intent_no(refund_no), UNIQUE KEY uk_refund_intent_order(out_order_no), KEY idx_refund_intent_status(intent_status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS d_refund_intent_1 LIKE d_refund_intent_0;
USE stellaris_pay_1;
CREATE TABLE IF NOT EXISTS d_refund_intent_0 LIKE stellaris_pay_0.d_refund_intent_0;
CREATE TABLE IF NOT EXISTS d_refund_intent_1 LIKE stellaris_pay_0.d_refund_intent_0;
