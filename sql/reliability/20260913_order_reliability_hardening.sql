-- Apply after 20260910_single_trade_stream_schema.sql.
-- Redis derives an intent from (program_id, user_id, request_id), so MySQL must
-- use the same idempotency boundary. Including program_id is less restrictive
-- than the legacy key and does not invalidate existing rows.
ALTER TABLE t_order_request
    DROP INDEX uk_order_request_user,
    ADD UNIQUE KEY uk_order_request_program_user (program_id, user_id, request_id);

CREATE TABLE IF NOT EXISTS d_payment_reconciliation_event (
    id BIGINT NOT NULL,
    order_number BIGINT NOT NULL,
    event_status VARCHAR(20) NOT NULL COMMENT 'PENDING/PROCESSING/WAITING/FAILED/SUCCEEDED/DEAD',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME NOT NULL,
    last_attempt_time DATETIME NULL,
    last_error VARCHAR(1000) NULL,
    create_time DATETIME NOT NULL,
    edit_time DATETIME NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_reconciliation_order (order_number),
    KEY idx_payment_reconciliation_retry (event_status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付事实与订单状态可靠对账';
