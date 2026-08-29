-- 创建订单 DLT 持久审计，按订单相同的 order_number,user_id 路由。
USE stellaris_order_0;

CREATE TABLE IF NOT EXISTS d_order_create_dlt_record_0 (
    id BIGINT NOT NULL,
    event_id BIGINT NULL COMMENT '旧版本消息可能没有可靠事件ID',
    order_number BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    program_id BIGINT NOT NULL,
    dlt_topic VARCHAR(255) NOT NULL,
    source_partition INT NOT NULL,
    source_offset BIGINT NOT NULL,
    payload LONGTEXT NOT NULL,
    exception_message VARCHAR(1000) NULL,
    record_status VARCHAR(20) NOT NULL,
    replay_count INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    edit_time DATETIME NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dlt_event (event_id),
    UNIQUE KEY uk_dlt_source (dlt_topic, source_partition, source_offset),
    KEY idx_dlt_order (order_number, user_id),
    KEY idx_dlt_status (record_status, edit_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='创建订单死信持久审计';

CREATE TABLE IF NOT EXISTS d_order_create_dlt_record_1 LIKE d_order_create_dlt_record_0;
CREATE TABLE IF NOT EXISTS d_order_create_dlt_record_2 LIKE d_order_create_dlt_record_0;
CREATE TABLE IF NOT EXISTS d_order_create_dlt_record_3 LIKE d_order_create_dlt_record_0;

USE stellaris_order_1;
CREATE TABLE IF NOT EXISTS d_order_create_dlt_record_0 LIKE stellaris_order_0.d_order_create_dlt_record_0;
CREATE TABLE IF NOT EXISTS d_order_create_dlt_record_1 LIKE stellaris_order_0.d_order_create_dlt_record_0;
CREATE TABLE IF NOT EXISTS d_order_create_dlt_record_2 LIKE stellaris_order_0.d_order_create_dlt_record_0;
CREATE TABLE IF NOT EXISTS d_order_create_dlt_record_3 LIKE stellaris_order_0.d_order_create_dlt_record_0;
