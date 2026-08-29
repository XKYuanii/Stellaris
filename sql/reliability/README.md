# 星演可靠性迁移清单

当前 v5 使用 Redis Lua + Redis Stream，不再创建或读写节目库 `d_order_intent_*`、`d_order_create_event_*`。

已有环境按顺序执行：

1. `20260826_order_inventory_operation.sql`：创建 Kafka 消费侧必要的节目库存幂等表，不包含 Outbox；
2. `20260826_seat_reservation_owner.sql`：为四张节目座位物理表增加 `reservation_id`、`seat_version` 和归属索引；
3. `20260826_drop_order_intent_outbox.sql`：删除旧 v5 Intent/创建订单 Outbox 物理表；
4. `20260827_reconcile_program_32_ticket_inventory.sql`：按物理座位事实修正 JJ20 演示节目的票档总数和剩余数；
5. `20260825_order_create_dlt_audit.sql`、`20260825_order_dlt_source_upgrade.sql`：保留 Kafka 消费侧 DLT 原文审计；
6. `20260825_reservation_transition_event.sql`：保留订单侧支付/取消迁移事件；
7. refund 相关迁移继续用于退款闭环，与本次创建订单事件改造无关。

`20260825_order_intent*`、`20260825_create_order_reliable_event.sql` 中的创建订单事件表仅是旧方案迁移历史，新部署不得执行其中的 Intent/Outbox 建表部分。

Kafka 生产端使用 `acks=all` 与幂等生产；消费端关闭自动提交，事务成功后手动确认，失败重试后进入 Kafka DLT。Redis 侧需要监控主 Stream、PEL、dead stream、到期释放任务和对账任务。

本地单 broker/单 Redis 环境只能验证链路，不能证明副本容灾。生产式表述至少要求 Kafka 多副本与 `min.insync.replicas`，Redis 主从/哨兵或 Cluster 与 AOF；即便如此也不宣称绝对零丢失。
