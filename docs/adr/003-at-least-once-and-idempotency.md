# ADR 003：至少一次投递加业务幂等

- 状态：语义继续采用；v5 的生产记录已由 Redis Stream 替代 MySQL Outbox

不依赖 Kafka Exactly Once 覆盖 Redis、MySQL 与订单服务。v5 在锁座 Lua 内写 Redis Stream，中继和 Kafka 都允许重复投递；消费者用 `eventId`、订单号和库存操作记录去重，DLT/dead 与订单、库存对账保证最终收敛。
