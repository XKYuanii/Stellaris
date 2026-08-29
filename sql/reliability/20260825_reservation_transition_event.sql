-- v5 支付/取消的订单本地可靠迁移事件。按 order_number,user_id 与订单使用相同分片算法。
USE stellaris_order_0;

CREATE TABLE IF NOT EXISTS d_reservation_transition_event_0 (
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
    UNIQUE KEY uk_transition_order (order_number),
    KEY idx_transition_retry (event_status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='v5座位状态迁移可靠事件';

CREATE TABLE IF NOT EXISTS d_reservation_transition_event_1 LIKE d_reservation_transition_event_0;
CREATE TABLE IF NOT EXISTS d_reservation_transition_event_2 LIKE d_reservation_transition_event_0;
CREATE TABLE IF NOT EXISTS d_reservation_transition_event_3 LIKE d_reservation_transition_event_0;

USE stellaris_order_1;

CREATE TABLE IF NOT EXISTS d_reservation_transition_event_0 LIKE stellaris_order_0.d_reservation_transition_event_0;
CREATE TABLE IF NOT EXISTS d_reservation_transition_event_1 LIKE stellaris_order_0.d_reservation_transition_event_0;
CREATE TABLE IF NOT EXISTS d_reservation_transition_event_2 LIKE stellaris_order_0.d_reservation_transition_event_0;
CREATE TABLE IF NOT EXISTS d_reservation_transition_event_3 LIKE stellaris_order_0.d_reservation_transition_event_0;
