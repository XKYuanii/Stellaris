-- 既有环境升级：DLT 以 Kafka 源位置去重，允许同一业务订单保留多次失败审计。
ALTER TABLE stellaris_order_0.d_order_create_dlt_record_0 DROP INDEX uk_dlt_order,
  ADD UNIQUE KEY uk_dlt_source(dlt_topic, source_partition, source_offset), ADD KEY idx_dlt_order(order_number, user_id);
ALTER TABLE stellaris_order_0.d_order_create_dlt_record_1 DROP INDEX uk_dlt_order,
  ADD UNIQUE KEY uk_dlt_source(dlt_topic, source_partition, source_offset), ADD KEY idx_dlt_order(order_number, user_id);
ALTER TABLE stellaris_order_0.d_order_create_dlt_record_2 DROP INDEX uk_dlt_order,
  ADD UNIQUE KEY uk_dlt_source(dlt_topic, source_partition, source_offset), ADD KEY idx_dlt_order(order_number, user_id);
ALTER TABLE stellaris_order_0.d_order_create_dlt_record_3 DROP INDEX uk_dlt_order,
  ADD UNIQUE KEY uk_dlt_source(dlt_topic, source_partition, source_offset), ADD KEY idx_dlt_order(order_number, user_id);
ALTER TABLE stellaris_order_1.d_order_create_dlt_record_0 DROP INDEX uk_dlt_order,
  ADD UNIQUE KEY uk_dlt_source(dlt_topic, source_partition, source_offset), ADD KEY idx_dlt_order(order_number, user_id);
ALTER TABLE stellaris_order_1.d_order_create_dlt_record_1 DROP INDEX uk_dlt_order,
  ADD UNIQUE KEY uk_dlt_source(dlt_topic, source_partition, source_offset), ADD KEY idx_dlt_order(order_number, user_id);
ALTER TABLE stellaris_order_1.d_order_create_dlt_record_2 DROP INDEX uk_dlt_order,
  ADD UNIQUE KEY uk_dlt_source(dlt_topic, source_partition, source_offset), ADD KEY idx_dlt_order(order_number, user_id);
ALTER TABLE stellaris_order_1.d_order_create_dlt_record_3 DROP INDEX uk_dlt_order,
  ADD UNIQUE KEY uk_dlt_source(dlt_topic, source_partition, source_offset), ADD KEY idx_dlt_order(order_number, user_id);
