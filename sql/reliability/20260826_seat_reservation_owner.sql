-- v5 Redis Stream 链路的 MySQL 座位归属 CAS。
-- 基础演示数据导入后执行一次；四张物理分表均需增加相同字段。
ALTER TABLE stellaris_program_0.d_seat_0
    ADD COLUMN reservation_id VARCHAR(64) NULL COMMENT '当前 v5 预订归属' AFTER sell_status,
    ADD COLUMN seat_version BIGINT NOT NULL DEFAULT 0 COMMENT '座位状态版本' AFTER reservation_id,
    ADD INDEX idx_program_reservation (program_id, reservation_id);

ALTER TABLE stellaris_program_0.d_seat_1
    ADD COLUMN reservation_id VARCHAR(64) NULL COMMENT '当前 v5 预订归属' AFTER sell_status,
    ADD COLUMN seat_version BIGINT NOT NULL DEFAULT 0 COMMENT '座位状态版本' AFTER reservation_id,
    ADD INDEX idx_program_reservation (program_id, reservation_id);

ALTER TABLE stellaris_program_1.d_seat_0
    ADD COLUMN reservation_id VARCHAR(64) NULL COMMENT '当前 v5 预订归属' AFTER sell_status,
    ADD COLUMN seat_version BIGINT NOT NULL DEFAULT 0 COMMENT '座位状态版本' AFTER reservation_id,
    ADD INDEX idx_program_reservation (program_id, reservation_id);

ALTER TABLE stellaris_program_1.d_seat_1
    ADD COLUMN reservation_id VARCHAR(64) NULL COMMENT '当前 v5 预订归属' AFTER sell_status,
    ADD COLUMN seat_version BIGINT NOT NULL DEFAULT 0 COMMENT '座位状态版本' AFTER reservation_id,
    ADD INDEX idx_program_reservation (program_id, reservation_id);
