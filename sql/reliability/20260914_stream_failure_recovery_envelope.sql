-- Apply once to an existing stellaris_trade database created before 2026-09-14.
-- Fresh databases already receive this column from 20260910_single_trade_stream_schema.sql.
ALTER TABLE d_order_stream_failure
    ADD COLUMN intent_id VARCHAR(128) NULL
        COMMENT 'Redis预约恢复信封；payload损坏时仍可安全释放'
        AFTER program_id;
