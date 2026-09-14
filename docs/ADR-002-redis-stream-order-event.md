# ADR-002：Redis Stream 直连单交易库

- 状态：已采用并实现
- 初始日期：2026-08-26
- 修订日期：2026-09-11
- 范围：v5/reference 创建订单与终态同步

## 背景

旧链路在 Redis 锁座后再经过 Kafka 创建订单，同时节目库座位和订单库订单分两次提交。它需要 Stream 中继、Kafka 重试/DLT、跨库库存操作、延迟取消队列和多源对账，仍不能原子解决节目库提交成功而订单库失败的窗口。

创建订单事件只在座位预占成功时产生，堆积量受销售库存约束。该链路当前只有订单服务一个业务消费者，不需要 Kafka 的多订阅者和长期日志能力。

## 决策

1. Program 在一个有界 Lua 中完成请求幂等、快速限购、座位预占和 XADD。
2. Order 直接通过 Redis Stream Consumer Group 消费，使用 Pending 重领恢复进程退出留下的记录。
3. 请求结果、账号最终限购、全部座位 CAS、订单与明细在 stellaris_trade 的一个本地事务中提交。
4. 事务形成 CREATED、REJECTED 或异常审计事实后才 ACK；XDEL 只是 ACK 后的空间清理。
5. 支付和取消在交易库中竞争订单唯一终态，并在同一事务写 Redis 待同步记录。
6. 未支付订单由数据库到期扫描关闭。
7. 创建订单 Kafka relay/consumer/DLT、Redis 延迟取消队列和跨库库存迁移退出运行主线。

    Gateway admission
      -> Redis O(k) Lua: idempotency + quota hint + reserve + XADD
      -> Redis Stream Consumer Group
      -> MySQL local transaction:
           request idempotency + authoritative quota + seat CAS + order
      -> ACK + best-effort XDEL

## Stream 处理

销售 Key 与 Stream 按 programId % 16 使用 {sale:shard} Hash Tag。同一节目涉及的 Redis Key 保持同槽，多键 Lua 可以在 Cluster 中执行。热门单场仍集中在一个槽，这是原子处理多座位的约束。

消费者批量读取、逐订单提交。数据库临时故障不 ACK，消息留在 PEL；恢复器对超过 claim-idle-ms 的消息执行 XPENDING + XCLAIM。重复执行由下列约束吸收：

- t_order_request.reservation_id 主键；
- UNIQUE(user_id, request_id)；
- order_number 唯一；
- 座位只允许 AVAILABLE -> LOCKED，并绑定 reservation/order；
- 账号额度使用带上限条件的原子更新。

无法解析或确认是永久业务毒消息时，先写 d_order_stream_failure，再 ACK。人工重放使用原 payload，仍经过相同数据库幂等约束。

## 背压

Lua 在任何库存修改之前检查当前分片 Stream 长度；达到 reference-order.max-stream-length 时返回 ORDER_BACKLOG_LIMIT。这是简单的接单保护，不替代对最老消息年龄、PEL、Redis 内存、数据库连接池和事务延迟的监控。阈值必须压测后设置。

## 数据与事务边界

t_seat_inventory 是座位销售唯一事实，不维护票档余票计数表。Redis available/owner/sold 是准入和展示模型，可以短暂领先或滞后 MySQL。

Redis 与 MySQL 不构成一个事务。Lua 成功表示“已受理”，MySQL 提交才表示“已成单”。锁座与事件不会出现 Java 两步双写窗口，但 Redis 异步复制切换仍可能丢最近写入；该方案不能描述为端到端 Exactly Once 或绝对零丢失。

## 结果

收益：

- 消除座位、账号额度和订单之间的跨业务库提交窗口；
- 删除 Kafka 创建订单中继及两套死信/重试；
- 删除 Redis 延迟取消队列和迁移服务默认入口；
- 每个恢复任务都有单一职责。

代价：

- Redis 承担受理阶段的短期可靠队列，需要验证 AOF、复制、备份和切换；
- 单交易库承担所有核心写入，需要以真实事务延迟和锁等待决定扩容时机；
- Stream 不提供 Kafka 式长期回放，长期审计以 MySQL 业务事实为准。

完整 Schema、状态矩阵、演进路径和验收标准见 [交易架构说明](SINGLE_TRADE_STREAM_ARCHITECTURE.md)。
