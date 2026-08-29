# 订单架构演进

## 目标

项目聚焦为一个有架构演进、性能数据、故障闭环和取舍说明的高并发票务面试项目。历史接口仅用于复现实验和比较；最终参考实现固定为 `v5/reference`。

| 版本 | 注册 Key | 阶段 | 核心方案 | 展示目的 |
| --- | --- | --- | --- | --- |
| v1 | `v1` | EXPERIMENTAL | MySQL 直接扣库存 | 最简单基线 |
| v2 | `v2` | EXPERIMENTAL | JVM 本地锁 | 单实例有效、多实例失效 |
| v21 | `v21` | EXPERIMENTAL | Redisson 节目级锁 | 分布式正确但串行化严重 |
| v3 | `v3` | EXPERIMENTAL | 细粒度座位锁 | 锁数量、顺序和续租复杂性 |
| v31 | `v31` | EXPERIMENTAL | Redis Lua 锁座 | 原子校验与修改 |
| v4 | `v4` | EXPERIMENTAL | 直接 Kafka 异步下单 | 双写丢消息窗口 |
| v41 | `v41` | EXPERIMENTAL | Outbox + 幂等消费 | 可靠事件 |
| v5 | `v5` | REFERENCE | 分层限流 + 有界 Lua/Stream + Kafka 幂等 + 对账 | 最终参考实现 |

## 注册约束

- `ProgramOrderVersion` 中每个注册 Key 必须唯一；启动测试覆盖此约束。
- `ProgramOrderContext` 遇到两个策略声明同一个 Key 时立即抛出异常，禁止依赖 Spring Bean 返回顺序覆盖。
- Swagger 中的 v1～v41 均标注为“实验”；v5 在最终策略实现完成前不暴露空路由。

## 参考链路

```text
网关 USER/PROGRAM/GLOBAL 分布式限流
  -> 本机并发隔离
  -> 计算有限候选座位
  -> 有界 Lua 原子锁座并 XADD Redis Stream
  -> Consumer Group 中继（Pending 重领/死信）
  -> Kafka 至少一次投递
  -> MySQL reservationId CAS + 订单服务幂等创建
  -> 支付 / 超时取消状态机
  -> Stream、订单、库存对账
```
