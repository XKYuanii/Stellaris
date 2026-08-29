# ADR-001：使用订单意图状态机闭合 Redis 锁座与 Outbox 之间的窗口

- 状态：已由 ADR-002 取代，不再属于当前 v5
- 日期：2026-08-25
- 范围：节目服务异步创建订单 V2 链路

## 背景

当前流程是 Redis Lua 原子锁座和扣减缓存余票，然后把完整订单事件写入 MySQL Outbox。两个存储不能参与同一本地事务；若进程在 Lua 成功后被硬终止，Java 异常处理无法运行，MySQL 中也没有事件可供中继扫描。

## 决策

现有项目采用“**MySQL 先写订单意图，Redis Lua 幂等预占，MySQL 再完成意图并写 Outbox**”的状态机。它是可恢复的 Saga，不宣称 Redis 与 MySQL 强原子，但保证每次 Redis 变更之前已经存在可扫描的持久意图。

不把 Redis Stream 选为当前主方案。Redis Stream 可以和库存变化在同一 Lua 中 `XADD`，但这会把 Redis 从缓存提升为订单事实日志，需要额外承担持久化、复制、Pending List、重领、裁剪、备份和容量治理；与项目现有 MySQL Outbox 重叠。

## 建议状态机

```text
INTENT_PENDING
      │ 幂等 Lua（reservationId）
      ▼
RESERVATION_CONFIRMED
      │ 同一 MySQL 事务：更新意图 + 插入 Outbox
      ▼
EVENT_READY ──Kafka 中继──▶ EVENT_SENT ──消费幂等──▶ ORDER_CREATED
      │
      └──超时/失败──▶ COMPENSATING ──幂等释放──▶ CANCELLED / MANUAL
```

## 写路径

1. 在节目分片库插入 `d_order_create_intent`，生成并保存 `reservationId`、`orderNumber`、节目、用户、购票人、选座请求和过期时间，状态为 `INTENT_PENDING`。
2. Lua 以 `reservationId` 作为幂等键：
   - 已存在预占结果时直接返回原结果；
   - 不存在时完成余票校验、扣减、锁座，并原子写入完整预占结果标记。
3. Java 根据 Lua 返回的最终座位组装订单事件。
4. 在同一个 MySQL 本地事务中把意图更新为 `EVENT_READY`，并插入 `d_order_create_event`。
5. 现有 Outbox 中继投递 Kafka；消费端继续使用订单号和库存操作表幂等。

## 恢复任务

定时扫描超时状态，所有转换使用条件更新，避免多实例重复处理：

| 当前状态 | Redis 预占结果 | 恢复动作 |
| --- | --- | --- |
| `INTENT_PENDING` | 存在 | 重建完整事件，在 MySQL 事务中转为 `EVENT_READY` 并写 Outbox |
| `INTENT_PENDING` | 不存在 | 未过期则重试幂等 Lua；已过期则标记 `CANCELLED` |
| `EVENT_READY` | 任意 | 确认 Outbox 存在；缺失时在本地事务补建 |
| `EVENT_SENT` | 存在且订单不存在 | 等待消费/DLT；超过业务期限进入人工或补偿流程 |
| `COMPENSATING` | 存在 | 按 `reservationId` 幂等释放座位和余票 |

恢复任务必须记录尝试次数、下次执行时间、最后错误，并提供 `pending/dead/age` 指标。

## 为什么这是当前项目最合理的方案

- MySQL 意图在 Redis 变更前已存在，`kill -9` 后仍能定位并恢复；
- 复用已有 MySQL 分片、Outbox 中继、订单幂等和库存对账；
- Lua 仍保留热点节目下的高并发原子校验能力；
- 状态可通过 SQL 查询、审计和人工重放，排障成本低于只存在 Redis Pending List；
- 不引入 XA，也不误把 Kafka 事务当成跨 MySQL/Redis 的全局事务。

## 其他方案

### 数据库作为库存唯一事实源 + Outbox

这是重新设计系统时最干净的长期方案：库存/座位状态和 Outbox 在同一数据库事务中提交，Redis 只做预检、限流和缓存。热点通过节目分片、请求排队和条件更新控制。缺点是对当前 Redis-first 链路改造最大。

### Lua 同时写 Redis Stream

它能原子完成锁座与 `XADD`，消费者可用 Consumer Group 和 `XAUTOCLAIM` 接管崩溃消费者。但必须保证 Stream 不被错误裁剪，并配置足够的 Redis 持久化与复制。Redis 官方文档明确指出默认异步复制和常见 AOF 策略仍可能丢失最近写入，因此它不是自动获得的“绝对可靠”。

### XA/Seata 覆盖 Redis 与 MySQL

不采用。Redis Lua 不适合直接成为传统 XA 资源；强行引入全局锁和长事务会放大热点链路延迟、阻塞与故障面。

## 验收条件

- 在 Lua 返回成功后的每一个故障点执行 `kill -9`，重启后都能由意图扫描恢复事件或释放库存；
- 同一 `reservationId` 重放 100 次，只产生一次库存变化、一个订单和一个逻辑事件；
- Kafka、订单服务或节目服务分别停机 30 分钟后恢复，事件最终送达且库存一致；
- `INTENT_PENDING` 最大年龄、恢复失败、Outbox backlog、DLT 和对账未运行均有告警；
- 迁移支持灰度开关和回滚，旧消息没有 `reservationId` 时仍能消费。

## 参考

- [Debezium Outbox Event Router](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html)
- [AWS Transactional Outbox Pattern](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)
- [Redis Streams 与 Consumer Groups](https://redis.io/docs/latest/develop/data-types/streams/)
- [Redis XAUTOCLAIM](https://redis.io/docs/latest/commands/xautoclaim/)
- [Redis Persistence](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)
- [Redis Replication](https://redis.io/docs/latest/operate/oss_and_stack/management/replication/)
