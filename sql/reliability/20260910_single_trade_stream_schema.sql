-- 单交易库 + Redis Stream 目标 Schema。
-- 非破坏性建表脚本：不迁移旧分片数据，也不删除旧表。
CREATE DATABASE IF NOT EXISTS stellaris_trade DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE stellaris_trade;

CREATE TABLE IF NOT EXISTS d_order (
    id BIGINT NOT NULL,
    order_number BIGINT NOT NULL,
    intent_id VARCHAR(64) NOT NULL,
    program_id BIGINT NOT NULL,
    program_item_picture VARCHAR(1024) NULL,
    user_id BIGINT NOT NULL,
    program_title VARCHAR(512) NULL,
    program_place VARCHAR(100) NULL,
    program_show_time DATETIME NULL,
    program_permit_choose_seat TINYINT NOT NULL,
    distribution_mode VARCHAR(256) NULL,
    take_ticket_mode VARCHAR(256) NULL,
    order_price DECIMAL(12,2) NULL,
    pay_order_type INT NULL,
    order_status INT NOT NULL DEFAULT 1,
    create_order_time DATETIME NOT NULL,
    expire_time DATETIME NOT NULL,
    expiry_retry_count INT NOT NULL DEFAULT 0,
    expiry_next_retry_time DATETIME NULL,
    expiry_last_error VARCHAR(1000) NULL,
    cancel_order_time DATETIME NULL,
    pay_order_time DATETIME NULL,
    create_time DATETIME NOT NULL,
    edit_time DATETIME NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_number (order_number),
    UNIQUE KEY uk_order_intent (intent_id),
    KEY idx_order_user_time (user_id, create_order_time),
    KEY idx_order_program (program_id),
    KEY idx_order_expiry (order_status, expire_time, id),
    KEY idx_order_expiry_retry (order_status, expiry_next_retry_time, expire_time, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单库订单事实';

CREATE TABLE IF NOT EXISTS d_order_ticket_user (
    id BIGINT NOT NULL,
    order_number BIGINT NOT NULL,
    program_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    ticket_user_id BIGINT NOT NULL,
    seat_id BIGINT NOT NULL,
    seat_info VARCHAR(100) NULL,
    ticket_category_id BIGINT NOT NULL,
    order_price DECIMAL(12,2) NULL,
    pay_order_price DECIMAL(12,2) NULL,
    pay_order_type INT NULL,
    order_status INT NOT NULL DEFAULT 1,
    create_order_time DATETIME NOT NULL,
    cancel_order_time DATETIME NULL,
    pay_order_time DATETIME NULL,
    create_time DATETIME NOT NULL,
    edit_time DATETIME NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_ticket_seat (order_number, seat_id),
    KEY idx_order_ticket_user (user_id, program_id),
    KEY idx_order_ticket_ticket_user (ticket_user_id),
    KEY idx_order_ticket_time (create_order_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单库订单购票人明细';

CREATE TABLE IF NOT EXISTS d_order_program (
    id BIGINT NOT NULL,
    program_id BIGINT NOT NULL,
    order_number BIGINT NOT NULL,
    create_time DATETIME NOT NULL,
    edit_time DATETIME NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_program_order (order_number),
    KEY idx_order_program_program (program_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单库节目订单关联';

CREATE TABLE IF NOT EXISTS t_seat_inventory (
    program_id BIGINT NOT NULL,
    seat_id BIGINT NOT NULL,
    ticket_category_id BIGINT NOT NULL,
    sell_status INT NOT NULL COMMENT '1 AVAILABLE, 2 LOCKED, 3 SOLD',
    reservation_id VARCHAR(64) NULL,
    order_number BIGINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    price_in_cents BIGINT NOT NULL,
    sale_version VARCHAR(64) NOT NULL,
    create_time DATETIME NOT NULL,
    edit_time DATETIME NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    PRIMARY KEY (program_id, seat_id),
    KEY idx_inventory_available (program_id, ticket_category_id, sell_status),
    KEY idx_inventory_order (order_number),
    KEY idx_inventory_reservation (reservation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='座位销售唯一事实';

CREATE TABLE IF NOT EXISTS t_account_program_purchase (
    program_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    purchase_count INT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (program_id, user_id),
    CONSTRAINT ck_purchase_count_nonnegative CHECK (purchase_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号场次权威限购计数';

CREATE TABLE IF NOT EXISTS t_order_request (
    reservation_id VARCHAR(64) NOT NULL,
    program_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    order_number BIGINT NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    result_status VARCHAR(20) NOT NULL COMMENT 'PROCESSING/CREATED/REJECTED',
    reject_code VARCHAR(64) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (reservation_id),
    UNIQUE KEY uk_order_request_program_user (program_id, user_id, request_id),
    UNIQUE KEY uk_order_request_number (order_number),
    KEY idx_order_request_result (result_status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Redis受理请求的MySQL最终结果';

CREATE TABLE IF NOT EXISTS d_reservation_transition_event (
    id BIGINT NOT NULL,
    command_id BIGINT NOT NULL,
    order_number BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    program_id BIGINT NOT NULL,
    intent_id VARCHAR(64) NOT NULL,
    target_sell_status INT NOT NULL,
    payload LONGTEXT NOT NULL,
    event_status VARCHAR(20) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME NOT NULL,
    last_attempt_time DATETIME NULL,
    last_error VARCHAR(1000) NULL,
    create_time DATETIME NOT NULL,
    edit_time DATETIME NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_transition_command (command_id),
    UNIQUE KEY uk_transition_order_target (order_number, target_sell_status),
    KEY idx_transition_retry (event_status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单终态到Redis的可靠同步记录';

CREATE TABLE IF NOT EXISTS d_order_stream_failure (
    id BIGINT NOT NULL,
    stream_key VARCHAR(255) NOT NULL,
    stream_id VARCHAR(64) NOT NULL,
    event_id BIGINT NULL,
    order_number BIGINT NULL,
    user_id BIGINT NULL,
    program_id BIGINT NULL,
    intent_id VARCHAR(128) NULL COMMENT 'Redis预约恢复信封；payload损坏时仍可安全释放',
    payload LONGTEXT NOT NULL,
    exception_message VARCHAR(1000) NULL,
    record_status VARCHAR(20) NOT NULL COMMENT 'RECORDED/MANUAL_REQUIRED/REPLAYED/RESOLVED',
    replay_count INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    edit_time DATETIME NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stream_failure_source (stream_key, stream_id),
    KEY idx_stream_failure_order (order_number, user_id),
    KEY idx_stream_failure_status (record_status, edit_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='创建订单Stream异常审计';

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
