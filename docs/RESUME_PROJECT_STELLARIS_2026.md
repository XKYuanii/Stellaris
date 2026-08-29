# 星演票务系统：简历介绍与项目陈述

本文只描述当前 v5/reference。项目是工程化面试原型，不填写未经压测验证的 QPS，不使用“零丢失”“Exactly Once”或“生产级强一致”。

## 简历项目条目

### 星演高并发票务交易系统

**项目背景：** 面向热门演出开票的瞬时洪峰、同座竞争、异步建单、超时取消和支付终态收敛，构建网关、节目库存、订单、支付和用户微服务，重点重构交易并发与故障闭环。

**技术栈：** Java 17、Spring Boot/Cloud、Gateway、MyBatis-Plus、ShardingSphere、MySQL、Redis/Lua/Stream、Kafka、Nacos、OpenFeign、Micrometer、Prometheus、Vue 3、Docker Compose。

**个人工作：**

- 设计分层准入：Gateway 从验签后业务体生成可信用户和节目维度，依次执行 USER、PROGRAM、GLOBAL Redis 令牌桶，限流通过后才进入本机并发舱壁，避免未经治理的洪峰直接占用交易容量。
- 将复杂选座拆成“Java 有界候选搜索 + Lua 最终原子确认”：Redis 以 ZSET/Hash 保存可售索引和元数据，Lua 只处理单次 1～6 个座位，在 O(k) 内完成价格、owner、账号限购、锁座和 `XADD`，不扫描全场。
- 以 16 个 `{sale:shard}` Hash Tag 组织节目库存与 Stream，同一节目所有 Lua Key 保证同槽、跨节目分散槽位，解决 Redis Cluster 多键脚本 CROSSSLOT 与单槽集中过度的问题。
- 构建 Redis Stream 到 Kafka 的至少一次闭环：Consumer Group 在 broker 确认后才 XACK，PEL 超时消息可重领，失败上限后原子进入 dead stream，并按原 eventId/orderNumber 显式重放；Kafka 消费端手动提交、重试并写 DLT 审计。
- 订单消费者以 orderNumber/eventId 幂等，节目库使用 `NO_SOLD + reservation_id` 条件更新和影响行数校验；支付/取消仅允许相同 reservationId 从 LOCK 迁移，阻止延迟取消释放后来订单的座位。
- 建设预订到期和对账任务：过期事件不能再锁 MySQL 座位，宽限期后仅在订单事实仍不存在时释放 Redis reservation；对账检查 Stream/dead、订单事实和 owner/reservation/available 不变量。

## 简历精简版（4 条）

- 针对热门演出构建 USER/PROGRAM/GLOBAL 分层令牌桶与并发舱壁，按后端容量控制进入交易核心的请求，而不是让 MySQL 直接承受入口洪峰。
- 将自动选座拆为 Java 有界候选搜索和 O(k) Lua 原子提交，在同一脚本内完成限购、精确锁座与 Redis Stream 事件生成；通过销售分片 Hash Tag 满足 Redis Cluster 同槽约束。
- 基于 Redis Stream Consumer Group 与 Kafka 实现至少一次异步建单，支持 PEL 重领、Redis dead stream、Kafka DLT 和原 eventId 重放；订单号、唯一约束和状态 CAS 吸收重复消息。
- 使用 MySQL reservationId 座位归属 CAS、支付/取消终态竞争、到期释放和多层对账完成最终收敛，并主动说明 Redis 持久化、单节点演示和未压测吞吐的边界。

## 30 秒介绍

> 星演是一个热门演出票务交易原型。我负责的核心是让洪峰进得来但不能冲垮后端：请求先按用户、节目和全局三层限流，再进入并发舱壁。节目服务从 Redis 读取有限候选，Lua 只对用户最终选择的少量座位做原子校验，同时写 owner、reservation 和 Redis Stream 事件。后台把 Stream 至少一次投递到 Kafka，订单消费者用订单号幂等，并用 MySQL reservationId CAS 锁定持久座位。支付和取消通过状态 CAS 决定赢家，失败消息、到期预订和对账任务负责收敛。

## 90 秒介绍

> 热门开票的难点不是把每个请求都处理完，而是先把进入交易核心的流量控制在容量内。Gateway 使用可信 userId/programId 依次执行用户桶、节目桶和全局桶，最后才占用本机并发 permit。查询和候选选择由 Redis 承担，MySQL 不直接面对入口洪峰。
>
> 我没有把完整业务塞进“大 Lua”。Java 从票档 ZSET 分页读取有限候选并计算连座，Lua 只处理最多 6 个目标座位，在 O(k) 内验证服务端价格、票档、owner 和账号限购，然后原子完成 AVAILABLE 到 HELD，并在同一脚本中 XADD 完整订单事件。这消除了 Lua 锁座成功到 Java 留下事件之间的 kill -9 窗口。所有 Key 使用同一个 `{sale:shard}`，因此 Redis Cluster 不会 CROSSSLOT。
>
> Stream 中继只有在 Kafka `acks=all` 确认后才 XACK；发送结果不确定会重复投递，所以订单侧按 orderNumber/eventId 幂等，节目数据库按 reservationId 做座位 CAS。消费失败有限重试后进入 Kafka DLT，生产侧长期失败进入 Redis dead stream。支付/取消只允许同一归属从 LOCK 迁移，预订过期后先拒绝旧事件，再在宽限期后双查订单事实决定是否释放。整体是至少一次和最终一致性，不是端到端 Exactly Once。

## 三个难点故事

### 1. 为什么取消 Intent/Outbox，改成 Lua + Stream

原来的 Intent/Lua/Outbox 可靠性更保守，但一次请求需要两段节目库事务，热路径长，也削弱了 Redis-first 项目的重点。当前方案让锁座和事件在同一 Lua 原子发生，请求路径不访问 Intent/Outbox。代价是 Redis 成为短期事件日志，可靠性依赖主从、AOF、PEL、dead 和备份；故障切换仍可能丢最近写，必须主动说明这个边界。

### 2. 为什么不用 Redisson 给每个座位加锁

锁不是免费正确性。多个座位需要排序加锁、续租、释放和异常处理，网络往返与锁对象数量也更高。这里的数据全部位于同一 Redis 槽，Lua 能在一次往返内对有限座位做 compare-and-set；脚本复杂度固定受票数上限约束。若脚本需要扫描全场或承担支付状态机，就应拆回 Java/数据库。

### 3. 为什么 Kafka 配好仍可能重复

Kafka 确认后到 Redis XACK 前进程退出、消费事务成功到 offset 提交前退出、网络超时和人工重放都会产生重复。目标不是阻止消息重复，而是让重复没有第二次业务效果：Kafka key 保序，eventId/orderNumber 追踪，订单唯一键幂等，座位使用 reservationId CAS，终态迁移使用条件更新。

## 主动边界

- 本地 Docker 单 broker/单 Redis 只能验证流程，不能证明副本故障容错。
- Redis Lua + Stream 缩短热路径但不提供 Redis/MySQL 强原子；AOF 和主从只缩小风险窗口。
- 16 个销售分片是演示折中；超热点单节目仍集中在一个槽，这是保证同节目多键原子的代价。
- 候选搜索有上限，极端碎片化可能出现可控假阴性；最终是否占座只由 Lua 决定。
- 当前没有新一轮高并发和 kill -9 实测数据，不能给出吞吐或恢复时延数字。

## 自检问题

- 为什么限流顺序是 USER -> PROGRAM -> GLOBAL -> bulkhead？
- 同一个 Lua 为什么能同时修改座位和写 Stream，Cluster 下如何保证同槽？
- Kafka 已成功但 XACK 失败会怎样，为什么不会重复建单？
- 预订过期释放和迟到 Kafka 消息如何避免重新锁座？
- 延迟取消 A 为什么不能释放后来 reservation B 的座位？
- Redis 主从+AOF 能否保证零丢失？你的准确表述是什么？
- 为什么不用 Redisson 多座位锁，什么时候反而应该用锁？
- 对账为什么不能直接“看到不一致就覆盖”？
