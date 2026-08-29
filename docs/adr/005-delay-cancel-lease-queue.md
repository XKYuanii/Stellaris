# ADR 005：延迟取消使用 lease/ACK，而不是取到即删除

Redis `stellaris:{delay:cancel}:pending` ZSET 保存到期任务，领取时 Lua 原子移动到同一 Hash Tag 下的 `processing` ZSET 并写 lease 截止时间；业务取消成功后 ACK 删除 payload。消费者崩溃时，另一节点把过期 lease 从 `processing` 放回 `pending`。订单状态已经是支付、取消或退款时同样 ACK，保证重复取消任务幂等成功。数据库扫描当前使用 `NO_PAY AND create_order_time + timeout < now` 兜底，待订单 `expire_time` 落库后替换为该字段。

现有 Redisson 延迟队列仍保留为 v1～v41 实验路径；v5 订单在本地事务 `afterCommit` 后写入上述队列，消费者和扫描器均默认不启用，先经故障演练后再开启。
