-- v5/reference 订单 Intent。必须先执行，再开启 v5 路由。
-- 表按 program_id 与座位、可靠事件使用相同的 2 库 2 表路由。

USE stellaris_program_0;

CREATE TABLE IF NOT EXISTS d_order_intent_0 (
    id BIGINT NOT NULL,
    intent_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    user_id BIGINT NOT NULL,
    program_id BIGINT NOT NULL,
    ticket_count INT NOT NULL,
    order_number BIGINT NULL,
    intent_status VARCHAR(32) NOT NULL,
    seat_snapshot LONGTEXT NULL,
    order_payload LONGTEXT NULL COMMENT '锁座前持久化的完整订单创建事件',
    amount DECIMAL(18,2) NULL,
    expire_time DATETIME NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NULL,
    version INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    edit_time DATETIME NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_intent_id (intent_id),
    UNIQUE KEY uk_order_intent_request (program_id, request_id),
    UNIQUE KEY uk_order_intent_order (program_id, order_number),
    KEY idx_order_intent_recovery (intent_status, expire_time),
    KEY idx_order_intent_program (program_id),
    KEY idx_order_intent_account_quota (program_id, user_id, intent_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='v5订单意图';

CREATE TABLE IF NOT EXISTS d_order_intent_1 LIKE d_order_intent_0;

USE stellaris_program_1;
CREATE TABLE IF NOT EXISTS d_order_intent_0 LIKE stellaris_program_0.d_order_intent_0;
CREATE TABLE IF NOT EXISTS d_order_intent_1 LIKE stellaris_program_0.d_order_intent_0;
