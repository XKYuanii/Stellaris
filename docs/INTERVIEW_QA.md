# 面试问答要点

## 为什么 Semaphore 不是 QPS 限流？

Semaphore 只限制同一时刻在执行的请求数，慢请求会长期持有许可，快请求会快速归还；它是本机并发隔离。QPS 由令牌随时间补充的令牌桶控制。本项目还将防重复提交和用户购票额度作为独立业务规则。

## 为什么不把所有选座放到 Lua？

Redis 脚本在主线程原子执行。全量 `HVALS`、JSON 解析和排序会放大其他命令的尾延迟。v5 让 Java 从 ZSET 读取有限候选并计算连座，Lua 只 O(k) 校验和提交；候选冲突则有限重试。

## Kafka 是否 Exactly Once？

不是。项目采用至少一次投递、稳定 eventId/orderNumber 幂等、状态机和对账，实现最终收敛。Kafka `acks=all` 也必须结合副本数和 `min.insync.replicas`；本地单节点演示不等于高可用。

## 为什么取消创建订单 Intent/Outbox？

当前 v5 选择 Redis-first 的简化链路：锁座、reservation 与 `XADD` 位于同一有界 Lua，消除 Lua 成功到 Java 发消息之间的进程窗口；消费组负责 PEL 重领、dead 和 Kafka 中继。它缩短热路径，但可靠性上限依赖 Redis 主从/AOF，因此不能宣称绝对零丢失。金融级 RPO=0 场景应改用数据库 Reservation + Outbox，而不是夸大本方案。

## 三层对账会直接把数据“改正确”吗？

不会。事件层检查 source/dead Stream；订单层比较订单与支付账单；库存层检查 ZSET、Owner、Reservation 与总座位数。到期任务只有在源事件还没交给 Kafka、订单不存在且 MySQL 没有该 reservation 锁座时才自动释放。事实矛盾会报告并进入重试或人工处置，不能为了报表归零而强制覆盖。
