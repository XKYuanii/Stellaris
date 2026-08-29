# 故障闭环矩阵

| 故障点 | 预期动作 | 当前状态 |
| --- | --- | --- |
| 网关本机满载 | Bulkhead 拒绝并返回 503；不宣称是 QPS 限流 | 已实现 |
| Redis 令牌桶不可用 | 查询按规则本地降级；下单/支付 fail-closed | 已实现，待 Redis 集成测 |
| Lua 锁座成功后进程终止 | 锁座与 `XADD` 同一 Lua；消费组继续中继 | 已实现，待 kill 演练 |
| Stream 中继进程终止 | 未 ACK 记录留在 PEL，超时由其他实例 claim | 已实现，待 Redis/Kafka 集成演练 |
| Kafka 已确认但 Redis ACK 失败 | Stream 重投，消费者按 eventId/orderNumber 幂等 | 已实现 |
| Kafka 消费失败 | 有限重试后进入 DLT，持久审计后手动按原消息重投 | 已实现 DLT 审计表、手动重放，待真实故障演练 |
| 超时取消节点崩溃 | `processing` lease 到期回收，DB 过期扫描兜底 | 已实现，默认关闭；仅 v5 `NO_PAY` 订单入队/扫描 |
| 支付回调重复或乱序 | CAS 状态机；订单状态与库存迁移事件同事务，节目侧异步幂等迁移 | 已实现；PROCESSING 有 lease 回收，待停服务演练 |
| Redis 座位键部分丢失 | fail-closed，禁止由可能滞后的 DB 直接重开售卖 | 已实现；库存对账报告缺失，不自动重建 |
| Stream/订单/库存数据漂移 | Stream/dead、订单/支付、Redis 座位三层核验 | 已实现执行器、dead 安全重放和指标；不覆盖无法证明的业务事实 |
| Redis 主节点故障切换 | AOF/主从缩小窗口，但不承诺最近写零丢失 | 设计边界，需集群故障演练量化 RPO |
