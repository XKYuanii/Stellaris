# 业务不变量

这些不变量是 v5/reference 的验收基准；任何对账或压测都必须报告是否满足。

1. 一个 `requestId` 在同一节目只派生一个 reservationId；一个 reservationId 只对应一组最终座位快照和原始事件。
2. 一张座位在任意时刻只能处于可售、被一个有效 reservation 锁定、或已售三者之一。
3. 同一节目：可售座位数 + 有效锁定座位数 + 已售座位数 = 总座位数。
4. 每个锁定 seatId 都必须在 `seat:owner` 中关联有效 reservation；每个 HELD reservation 必须能找到座位和原始 Stream eventId。
5. 所有价格和票档以 `seat:meta` 的服务端快照为准，不信任客户端金额。
6. 订单、支付、退款和座位终态只能沿状态机允许的边迁移。
7. Kafka 为至少一次：重复事件不得创建第二个订单、第二次扣库存或第二次退款。

## 当前覆盖范围

- v5 Lua 通过 reservationId 重放结果，原子完成 Redis 预占与 `XADD`，并校验服务端价格和票档。
- Stream 消费组采用 PEL 重领、有限重试和 dead stream；Kafka 消费以 eventId/orderNumber 和库存操作记录幂等。
- 三层执行器核验 Stream/dead、订单/支付账单，以及 `seat:meta`、`available`、`owner`、`reservation` 的库存关系，并输出异常指标。
- v5 HTTP 下单、模拟支付、dead 重放和支付/取消库存迁移已经接通；仍须按 `RELIABILITY_EXERCISES.md` 用真实故障与并发报告证明端到端不变量。
