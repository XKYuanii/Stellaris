-- 创建订单消费侧的节目库存幂等事实；不包含 MySQL Intent 或创建订单 Outbox。
USE stellaris_program_0;

CREATE TABLE IF NOT EXISTS d_order_inventory_operation_0 (
    id BIGINT NOT NULL COMMENT '主键',
    order_number BIGINT NOT NULL COMMENT '订单号/库存操作幂等键',
    event_id BIGINT NOT NULL COMMENT 'Redis Stream/Kafka 事件ID',
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

CREATE TABLE IF NOT EXISTS d_order_inventory_operation_0 LIKE stellaris_program_0.d_order_inventory_operation_0;
CREATE TABLE IF NOT EXISTS d_order_inventory_operation_1 LIKE stellaris_program_0.d_order_inventory_operation_0;
