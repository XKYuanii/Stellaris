# 2026-08-26 Redis-first 主链整改结果

## 结论

本轮已按“简单 Redis -> Kafka -> MySQL”方向重构 v5/reference。创建订单热路径彻底取消节目库 `OrderIntent` 与 `OrderCreateEvent/Outbox`，不是把它们移动到后台。退款 Intent 和支付/取消迁移命令属于订单终态的另一条链路，不是被取消的创建订单阶段。

当前在线路径：

```text
Gateway USER/PROGRAM/GLOBAL 令牌桶
  -> 本机并发舱壁
  -> Redis 有界候选
  -> O(k) Lua: 限购 + HELD + reservation + XADD
  -> Redis Stream Consumer Group
  -> Kafka
  -> MySQL 座位 reservationId CAS + 唯一订单
  -> PAY/CANCEL CAS
  -> Redis SOLD/RELEASED
```

## 已完成

- 删除创建订单 Intent/Outbox 的实体、Mapper、Service、恢复/发布任务、分片规则和专属测试。
- 新增旧表删除迁移，Docker 初始化不再执行旧建表脚本，并在基础数据后补座位 reservation 字段。
- Lua 只处理最多 6 个最终座位，并将锁座与 `XADD` 置于同一原子操作。
- 所有 Lua Key 以 `programId % 16` 的 `{sale:shard}` Hash Tag 同槽，避免 Redis Cluster `CROSSSLOT`。
- Stream 中继在 Kafka broker 确认后 ACK；支持 PEL 重领、有限重试、dead stream 和保留原 eventId/orderNumber 的重放。
- 增加带 TTL 的 Redis 请求回执，修复自动选座成功后响应丢失、重试重新选座导致幂等冲突的问题。
- MySQL 座位用 `NO_SOLD + reservation_id IS NULL` 条件更新，严格校验影响行数；终态迁移校验 reservationId，防止 ABA 误释放。
- 到期事件在 MySQL 锁座前拒绝；自动清理仅释放仍留在源 Stream、订单不存在且节目库未锁座的 reservation。
- 网关按可信用户、节目、全局三层限流，限流通过后才占用本机并发 permit。
- 告警已从 MySQL Outbox backlog 改为 Redis Stream PEL/dead/relay 指标。

## 明确边界

- Redis 主从异步复制与 AOF fsync 仍可能在故障切换时丢失最近写，不能宣称 RPO=0 或绝对零丢失。
- Stream/Kafka 语义为至少一次；正确性依赖 eventId/orderNumber、库存操作唯一记录和状态 CAS。
- 目前完成静态检查、完整 Reactor 编译和主链单元测试；Redis Cluster、Kafka 故障、`kill -9`、全前端链路与高并发结果必须在后续演练后才能写入简历数字。
- 如果未来目标从面试原型变成金融级生产系统，应重新评估数据库 Reservation + Transactional Outbox/CDC，而不是继续扩大 Lua。

## 数据库迁移

新环境由 `ops/docker-compose.interview.yml` 依次执行：

1. 星演基础库与分表；
2. `20260826_order_inventory_operation.sql`（消费侧库存幂等事实，不是 Outbox）；
3. 支付/取消可靠命令与 DLT 审计表；
4. `20260826_seat_reservation_owner.sql`；
5. `20260826_drop_order_intent_outbox.sql`。

已有 MySQL 数据卷不会重新运行 Docker 初始化脚本，必须手工执行第 3、4 项并先备份。删除脚本只针对 `stellaris_program_0/1` 中 8 张旧创建阶段表。
