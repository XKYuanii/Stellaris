-- 创建订单 Kafka 可靠事件表。
-- 项目当前按 program_id 的基因位分到两个库、每库两个物理表，因此四个物理节点都要创建。

USE stellaris_program_0;

CREATE TABLE IF NOT EXISTS d_order_create_event_0 (
    id BIGINT NOT NULL COMMENT '主键',
    event_id BIGINT NOT NULL COMMENT '可靠事件ID',
    order_number BIGINT NOT NULL COMMENT '订单号',
    program_id BIGINT NOT NULL COMMENT '节目ID/分片键',
    intent_id VARCHAR(64) NULL COMMENT 'v5订单Intent标识',
    order_version INT NULL COMMENT '创建策略版本',
    topic VARCHAR(255) NOT NULL COMMENT 'Kafka topic',
    payload LONGTEXT NOT NULL COMMENT '订单事件JSON',
    send_status TINYINT NOT NULL DEFAULT 0 COMMENT '-1失败 0待发送 1发送中 2已发送 3人工处理',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '发送次数',
    next_retry_time DATETIME NOT NULL COMMENT '下次重试时间',
    last_attempt_time DATETIME NULL COMMENT '最后尝试时间',
    sent_time DATETIME NULL COMMENT '发送成功时间',
    last_error VARCHAR(1000) NULL COMMENT '最后错误',
    create_time DATETIME NOT NULL,
    edit_time DATETIME NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_create_event_id (event_id),
    UNIQUE KEY uk_order_create_event_order (order_number),
    UNIQUE KEY uk_order_create_event_intent (program_id, intent_id),
    KEY idx_order_create_event_retry (send_status, next_retry_time),
    KEY idx_order_create_event_program (program_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='创建订单可靠事件';

CREATE TABLE IF NOT EXISTS d_order_create_event_1 LIKE d_order_create_event_0;

CREATE TABLE IF NOT EXISTS d_order_inventory_operation_0 (
    id BIGINT NOT NULL COMMENT '主键',
    order_number BIGINT NOT NULL COMMENT '订单号/库存操作幂等键',
    event_id BIGINT NOT NULL COMMENT '创建订单事件ID',
    program_id BIGINT NOT NULL COMMENT '节目ID/分片键',
    create_time DATETIME NOT NULL,
    edit_time DATETIME NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_inventory_order (order_number),
    KEY idx_order_inventory_event (event_id),
    KEY idx_order_inventory_program (program_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='创建订单库存操作幂等记录';

CREATE TABLE IF NOT EXISTS d_order_inventory_operation_1 LIKE d_order_inventory_operation_0;

USE stellaris_program_1;

CREATE TABLE IF NOT EXISTS d_order_create_event_0 (
    id BIGINT NOT NULL COMMENT '主键',
    event_id BIGINT NOT NULL COMMENT '可靠事件ID',
    order_number BIGINT NOT NULL COMMENT '订单号',
    program_id BIGINT NOT NULL COMMENT '节目ID/分片键',
    intent_id VARCHAR(64) NULL COMMENT 'v5订单Intent标识',
    order_version INT NULL COMMENT '创建策略版本',
    topic VARCHAR(255) NOT NULL COMMENT 'Kafka topic',
    payload LONGTEXT NOT NULL COMMENT '订单事件JSON',
    send_status TINYINT NOT NULL DEFAULT 0 COMMENT '-1失败 0待发送 1发送中 2已发送 3人工处理',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '发送次数',
    next_retry_time DATETIME NOT NULL COMMENT '下次重试时间',
    last_attempt_time DATETIME NULL COMMENT '最后尝试时间',
    sent_time DATETIME NULL COMMENT '发送成功时间',
    last_error VARCHAR(1000) NULL COMMENT '最后错误',
    create_time DATETIME NOT NULL,
    edit_time DATETIME NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_create_event_id (event_id),
    UNIQUE KEY uk_order_create_event_order (order_number),
    UNIQUE KEY uk_order_create_event_intent (program_id, intent_id),
    KEY idx_order_create_event_retry (send_status, next_retry_time),
    KEY idx_order_create_event_program (program_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='创建订单可靠事件';

CREATE TABLE IF NOT EXISTS d_order_create_event_1 LIKE d_order_create_event_0;

CREATE TABLE IF NOT EXISTS d_order_inventory_operation_0 (
    id BIGINT NOT NULL COMMENT '主键',
    order_number BIGINT NOT NULL COMMENT '订单号/库存操作幂等键',
    event_id BIGINT NOT NULL COMMENT '创建订单事件ID',
    program_id BIGINT NOT NULL COMMENT '节目ID/分片键',
    create_time DATETIME NOT NULL,
    edit_time DATETIME NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_inventory_order (order_number),
    KEY idx_order_inventory_event (event_id),
    KEY idx_order_inventory_program (program_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='创建订单库存操作幂等记录';

CREATE TABLE IF NOT EXISTS d_order_inventory_operation_1 LIKE d_order_inventory_operation_0;
