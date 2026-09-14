# ADR 003：至少一次投递加业务幂等

- 状态：语义继续采用；v5 的生产记录已由 Redis Stream 替代 MySQL Outbox

当前 v5 不依赖 Kafka。锁座 Lua 写 Redis Stream，订单服务直接以 Consumer Group 消费。消息允许重复；消费者使用 reservationId 主键、(userId, requestId)、orderNumber 唯一约束以及座位/订单状态 CAS 保证业务只生效一次。永久毒消息先持久化到 `d_order_stream_failure` 再 ACK。
