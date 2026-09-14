-- 单交易库 d_order：过期关单失败项持久化退避，避免扫描队头阻塞。
USE stellaris_trade;

ALTER TABLE d_order
    ADD COLUMN expiry_retry_count INT NOT NULL DEFAULT 0 AFTER expire_time,
    ADD COLUMN expiry_next_retry_time DATETIME NULL AFTER expiry_retry_count,
    ADD COLUMN expiry_last_error VARCHAR(1000) NULL AFTER expiry_next_retry_time,
    ADD INDEX idx_order_expiry_retry
        (order_status, expiry_next_retry_time, expire_time, id);
