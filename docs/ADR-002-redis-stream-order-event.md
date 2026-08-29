# ADR-002：锁座 Lua 同时写 Redis Stream

- 状态：已采用
- 日期：2026-08-26
- 范围：v5/reference 创建订单链路

## 决策

v5 完全取消 MySQL `OrderIntent` 和创建订单 `Outbox` 阶段。请求经过 USER/PROGRAM/GLOBAL 分层限流和本机舱壁后，只访问预热的 Redis 座位模型。单次有界 Lua 对用户选择的 1～6 个座位执行元数据校验、账号配额、`AVAILABLE -> HELD` 和 `XADD`。

```text
Gateway admission
  -> Redis O(k) Lua: quota + hold + XADD
  -> Redis Stream relay
  -> Kafka
  -> MySQL seat owner CAS + order transaction
```

Stream 使用 Consumer Group。16 个分片各由独立 Worker 通过 `XREADGROUP BLOCK 1000ms COUNT n` 实时读取新消息；Kafka 发送全异步，并由全局 in-flight permit 和有界 ACK Executor 背压。中继仅在 Kafka broker 确认后 `XACK`，之后尽力 `XDEL`。进程退出留下的 PEL 消息由每 10 秒运行的独立恢复器处理，不阻塞新消息读取。

恢复器通过 `XPENDING + XCLAIM` 认领 idle 至少 60 秒且不在本机 in-flight 集合的消息。项目使用的 Spring Data Redis 版本没有暴露 `XAUTOCLAIM` 高级接口，该组合在当前版本完成相同的“查询超时 PEL 后认领”语义。达到次数上限后，同槽 Lua 原子迁移到 dead stream、确认并删除源记录，同时提供保持原 eventId/orderNumber 的显式重放。每个 reservationId 另有带 TTL 的 Redis receipt，使响应丢失后的重试在重新自动选座前返回原订单号；这不是 MySQL Intent。

链路使用低基数 Micrometer 指标记录 Stream 等待、Kafka 发送、订单创建和端到端耗时，以及 PEL、dead、in-flight 数量。订单号和 eventId 仅写日志，不进入 tag。

## Cluster 键槽

节目按 `programId % 16` 落到 `{sale:shard}`。同一节目的 meta、available、owner、reservation、配额、过期索引和该分片 Stream 都带相同 Hash Tag，因此多键 Lua 在 Redis Cluster 中同槽执行；不同节目可分散到 16 个槽。

## 取舍与边界

- 优点：热路径没有两次 MySQL Intent/Outbox 事务；锁座和事件不存在 Java `kill -9` 中间窗口；结构更适合突出 Redis 高并发裁决。
- 代价：Redis 从缓存提升为短期可靠事件源，必须配置主从、AOF、Stream PEL 监控和备份；异步复制故障切换仍可能丢最近写入。
- Kafka 发送确认和 `XACK` 之间允许重复，订单侧必须按 eventId/orderNumber 幂等。
- `XACK` 成功而 `XDEL` 失败只产生已确认的存储残留，不能重新投递；Kafka 成功而 `XACK` 失败则允许重复投递。
- claim idle 必须大于 Kafka producer 的 delivery timeout；当前分别为 60 秒和 30 秒。
- 预订事件携带截止时间；过期事件不能再锁 MySQL 座位。宽限期后仅在源 Stream 记录仍存在、订单事实不存在且 MySQL 未锁定该 reservation 时释放 Redis 预订。
- MySQL 座位使用 `sell_status=NO_SOLD`、`reservation_id` 和影响行数进行 CAS；延迟取消不能释放后来订单的座位。

本项目面向面试演示，不把该方案描述为端到端 Exactly Once 或绝对零丢失。
