# 星演票务系统 2026 新面试题库（真实面经来源版）

## 0. 先说明“确定性”

没有任何题目能保证 100% 被某一位面试官问到。这里的“必问”采用更严格、也更有用的定义：

1. 近年真实 Java 后端面经中反复出现；
2. 你的简历主动写了 Redis、Lua、Kafka、MySQL、分库分表、限流和高并发票务，面试官自然会沿这些关键词追问；
3. 问题可以直接落到当前仓库代码，无法靠背通用八股蒙混过去。

因此 S 级是“只要面试官认真拷打这个项目，极高概率触发”的题；A级是 Java 后端一二面反复出现的基础题。技术答案优先用 Kafka、Redis、MySQL、Spring、ShardingSphere、Oracle JDK 官方文档校验，牛客只用于证明“真实面试问过什么”，不把个人帖子当作技术标准。

## 1. 来源索引

### 真实面经

- N1：[京东 Java 二面（2025）：高并发下单、幂等、缓存一致性、限流与 MQ 选型](https://www.nowcoder.com/discuss/796272671347445760)
- N2：[快手 Java 一面（2025）：令牌桶/漏桶、重复消费、Kafka、MySQL 隔离与日志](https://www.nowcoder.com/discuss/722397808745033728)
- N3：[字节后端实习面经：秒杀流程、数据一致性、Redis 集群、MySQL 事务](https://www.nowcoder.com/discuss/353157449513377792)
- N4：[多厂 Java 面经合集：Kafka 高可用、Redis/DB 一致性、分库分表扩容、Redis 锁](https://www.nowcoder.com/discuss/357528225717772288)
- N5：[2026 Java 后端真题汇总：MySQL、Redis、并发、线程池与项目表达](https://www.nowcoder.com/discuss/864594486704291840)
- N6：[快手 Java（2025）：Redis 与数据库一致性、延迟双删和 HA](https://www.nowcoder.com/discuss/796841843760590848)
- N7：[字节 25 届面经：Redis 数据结构、缓存一致性、对账思路](https://ac.nowcoder.com/discuss/1509544?type=0)
- N8：[多厂社招面经：Kafka offset、重复消费、Spring 事务、Redis 锁、QPS 追问](https://www.nowcoder.com/discuss/855750)
- N9：[字节秒杀系统设计：限流、库存预扣、异步化和超时释放](https://www.nowcoder.com/discuss/966273)
- N10：[2026 面经话题：超卖、Redis 与 DB 事务一致性、Redisson 深挖](https://www.nowcoder.com/creation/subject/51e75405f5094d87bafa4e56d970bf69)
- N11：[Java 高频题整理：分布式 ID、消息可靠性、秒杀与限流](https://www.nowcoder.com/discuss/911366869016141824)

### 官方校验资料

- K1：[Apache Kafka Producer Configs：acks 与 idempotence](https://kafka.apache.org/31/configuration/producer-configs/)
- K2：[Apache Kafka Topic Configs：min.insync.replicas](https://kafka.apache.org/41/configuration/topic-configs/)
- K3：[KafkaProducer 4.1 API：幂等生产与事务边界](https://kafka.apache.org/41/javadoc/org/apache/kafka/clients/producer/KafkaProducer.html)
- K4：[Spring Kafka：DefaultErrorHandler、手动 ACK 与 DeadLetterPublishingRecoverer](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)
- R1：[Redis Cluster：Hash Slot 与 Hash Tag](https://redis.io/docs/latest/operate/oss_and_stack/management/scaling/)
- R2：[Redis Lua：脚本原子执行及阻塞语义](https://redis.io/docs/latest/develop/interact/programmability/eval-intro/)
- R3：[Redis EVAL：所有访问的 Key 必须作为 KEYS 显式传入](https://redis.io/docs/latest/commands/eval/index.html.md)
- R4：[Redis Streams：Consumer Group、PEL、XACK 与故障恢复](https://redis.io/docs/latest/develop/data-types/streams/)
- R5：[Redis 持久化：RDB、AOF 与数据安全边界](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)
- M1：[MySQL InnoDB 隔离级别与锁行为](https://dev.mysql.com/doc/refman/26.7/en/innodb-transaction-isolation-levels.html)
- M2：[MySQL：UPDATE/DELETE/锁定读会设置哪些锁](https://dev.mysql.com/doc/refman/26.7/en/innodb-locks-set.html)
- S1：[Spring `@Transactional`：代理模式、自调用和回滚规则](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
- S2：[Spring 声明式事务的 AOP 实现](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-decl-explained.html)
- H1：[ShardingSphere：自定义/复杂分片算法与 IN 路由](https://shardingsphere.apache.org/document/current/en/user-manual/common-config/builtin-algorithm/sharding/)
- H2：[ShardingSphere 分片概念：单分片、多分片与自定义算法职责](https://shardingsphere.apache.org/document/5.4.1/en/features/sharding/concept/)
- J1：[JDK 17 并发包的 happens-before 规则](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/package-summary.html)
- J2：[JDK 17 CompletableFuture 默认执行器语义](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletableFuture.html)
- G1：[Spring Cloud Gateway RateLimiter：令牌桶、KeyResolver 与 429](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webmvc/filters/ratelimiter.html)

---

## 2. S 级：项目拷打极高概率题

### S01. 用一分钟讲清楚一次 v5 下单的数据流

**30 秒答案：**

请求先经过 Gateway 的 USER/PROGRAM/GLOBAL 令牌桶和本机并发舱壁。节目服务从 Redis 有界候选集中选出最多 6 个座位；单个 O(k) Lua 校验票档、价格、owner 和账号限购，同时完成 `AVAILABLE -> HELD`、reservation、到期索引和 `XADD`。Redis Stream 消费组中继收到 Kafka broker 确认后再 `XACK`，超限进入 dead stream。订单消费者手动 ACK，先用 reservationId 和条件更新幂等锁定节目库座位，再创建唯一订单。支付、取消和超时用 CAS 决定终态，迁移 Redis 为 SOLD/RELEASED；调度对长期偏差做保守对账。

**追问陷阱：** 不要按模块罗列 Controller/Service/Mapper；必须说出每一步的权威事实、幂等键和失败恢复者。

**项目证据：** `ReferenceOrderOrchestrator`、`referenceReserveSeats.lua`、`RedisOrderCreateRealtimeRelay`、`RedisOrderCreatePendingRecovery`、`RedisOrderCreatePublisher`、`CreateOrderConsumer`、`ReferenceReservationTransitionService`。

**面经依据：** N3 明确问“项目运行流程”和“一致性”；N1/N8 强调项目数据流与技术选型。

### S02. 为什么一个 Lua 锁座通常比分布式锁包围整段业务快？

**答案：**

分布式锁至少包含加锁、业务读写、解锁，多次网络往返；持锁期间其他请求串行等待，还要处理租约、续期和锁失效。Lua 把少量 Redis 读写在服务端一次执行，只有一次网络往返，并利用 Redis 脚本原子执行完成“校验全部座位后一次提交”。

但 Lua 不是天然更快：脚本执行期间 Redis 会阻塞其他活动，若在 Lua 内 `HVALS` 全场、JSON 解码和排序，延迟会比短锁更糟。星演 v5 让 Java 做有界候选计算，Lua 只校验最终 k 个座位，复杂度接近 O(k)。Redis 官方明确说明脚本原子执行期间会阻塞服务端活动，见 R2。

**追问：** Lua 能代替 MySQL 事务吗？不能。它只在单个 Redis 执行上下文内原子，不能与 MySQL/Kafka 一起提交。

### S03. 你怎么防止同一个座位超卖？

**答案：**

防超卖不是“用了 Redis”这么简单，而是三层不变量：

1. Lua 在一次执行中检查 ready、maintenance、seat meta、owner、可售 ZSET、票档和价格；任一座位失败则不写任何 owner/reservation。
2. MySQL 座位状态从 NO_SOLD 到 LOCK 使用条件和库存操作唯一键，重复 Kafka 消息不重复扣减。
3. 支付/取消终态必须用订单状态 CAS 决定赢家，再以稳定 commandId 幂等迁移 SOLD/RELEASED。

**当前代码边界：** 三层均已接入；尚未用高并发与 `kill -9` 演练给出吞吐数字，因此不能把设计验证冒充压测结论。

**面经依据：** N3、N9、N10、N11 都会从“超卖”继续追到 Redis/DB 一致性。

### S04. Lua 成功后服务立刻 `kill -9`，会不会丢订单？

**答案：**

当前 v5 不存在“Lua 已锁座但 Java 还没留下事件”的进程窗口：锁座状态、reservation 和 `XADD` 在同一个 Redis Lua 内原子完成。即使进程在脚本返回前 `kill -9`，Stream 中仍有同一个 eventId/orderNumber，消费组可以继续中继；请求重试以稳定 reservationId 返回原结果，不会重复锁座。

但这不是“绝对不丢”：Redis 主从复制通常异步，故障切换以及 AOF 的 fsync 策略都可能形成最近写丢失窗口。当前取舍是缩短热路径，依靠 Redis 主从/AOF、PEL 重领、dead stream、Kafka 幂等和对账降低风险，并明确不宣称跨存储强一致。

**追问：** 为什么不保留 MySQL Intent？因为本项目选择突出 Redis-first 的抢票吞吐，接受 Redis 作为短期可靠事件载体；若业务要求金融级 RPO=0，应该换成数据库 Reservation + Outbox 或共识日志，而不是继续吹 Redis 零丢失。

### S05. 为什么这里取消 MySQL Intent/Outbox，仍然不直接在请求线程发 Kafka？

**答案：**

请求线程在 Lua 后直接发 Kafka 会重新出现 `kill -9` 窗口。当前把 `XADD` 放入同一 Lua，让 Redis 锁座和待发送事件同生共死；后台消费组中继负责 Kafka 发送、PEL 超时重领、有限重试和 dead stream。Kafka 确认前不 ACK，重复投递由 eventId、orderNumber、库存操作唯一键和状态 CAS 吸收。

这是用 Redis Stream 替换“创建订单生产侧 Outbox”，不是否认 Outbox 模式。代价是可靠性上限受 Redis 持久化和故障切换影响；系统语义仍是至少一次和最终一致，不是 Exactly Once。

**面经依据：** N1/N4/N8 反复追问 MQ 数据一致性、消息不丢和重复消费。

### S06. `acks=all` 能保证消息绝不丢吗？

**答案：**

不能。`acks=all` 表示 leader 等待当前 ISR 的确认，是 Kafka 可用的最强 producer ack；`enable.idempotence=true` 还要求 acks=all、retries>0、in-flight<=5，主要消除同一 producer session 的重试重复，见 K1/K3。

它的耐故障能力取决于副本拓扑和 `min.insync.replicas`。星演 Docker 只有 1 个 broker、replication factor=1、min ISR=1，所以 acks=all 只代表单副本确认，不能承受 broker/磁盘损坏。生产建议 3 副本、min ISR=2，语义见 K2。

**禁止回答：** “acks=all 就绝不会丢”。

### S07. 为什么消费者要手动提交？在什么时机 ACK？

**答案：**

只有业务落库完成后才 `acknowledge()`。如果先提交 offset 再处理，处理中崩溃会跳过消息；如果业务成功但 ACK 前崩溃，消息会重复，因此订单创建和库存更新必须幂等。空消息可以按明确策略 ACK，业务异常抛出交给 `DefaultErrorHandler` 重试/DLT。

Spring Kafka 对 `MANUAL_IMMEDIATE`、recovered offset 和 DLT 的行为有明确配置，见 K4。

**项目证据：** `CreateOrderConsumer` 成功创建订单后 ACK；`KafkaConsumerReliabilityConfiguration` 设为 MANUAL_IMMEDIATE。

### S08. 消费者重复消费怎么保证只生效一次？

**答案：**

不要只用 Redis `SETNX`：Redis 成功后业务事务回滚会把消息永久标为已消费。更可靠的是同一数据库本地事务内使用业务唯一约束或消费记录唯一键，并以状态 CAS 更新业务。星演使用 orderNumber、eventId、intentId、库存操作唯一键；重复消息先查已存在订单，并且数据库唯一索引作为最后防线。

还要校验“同一个幂等键的 payload 是否相同”。只看到订单存在就返回，而不核对 programId、userId、金额和 intentId，会把上游错误隐藏成幂等成功。

**面经依据：** N2 明确问“什么情况下重复收到消息、如何避免重复消费”；N8 追问消费失败和重复消费。

### S09. 重试和 DLT 应该怎样设计？毒 JSON 怎么办？

**答案：**

瞬时异常有限重试并退避；参数错误、反序列化错误等不可恢复异常尽快进入 DLT。DLT listener 第一件事不是解析业务对象，而是按 source topic/partition/offset 唯一保存原始 key/value/headers/exception；业务字段 best-effort 解析且允许为空。审计成功才 ACK，重放沿用原 eventId/orderNumber，避免生成新业务。

DLT listener 必须使用专用 error handler，不能失败后再投 `topic + .DLT`，否则形成 `.DLT.DLT`。Spring 的 `DeadLetterPublishingRecoverer` 和 error handler 语义见 K4。

**当前代码边界：** 现在先 parse JSON，审计表 orderNumber NOT NULL，且 DLT 共用默认 factory；这是必须主动承认并修的 P0。

### S10. 支付和取消同时到达，最终状态由谁决定？

**答案：**

最终必须由订单数据库的条件更新决定：

```sql
UPDATE d_order
SET order_status = :target, edit_time = NOW()
WHERE order_number = :orderNo
  AND order_status = :NO_PAY;
```

`affectedRows=1` 的请求是唯一赢家；输家重新读取终态并返回幂等或冲突。只有赢家能在同一事务中写对应 `ReservationTransitionEvent`。分布式锁可以降低竞争，但不能替代 CAS，因为锁可能超时、主从切换或不同代码路径没拿同一把锁。

MySQL 对 UPDATE 的匹配和锁行为见 M1/M2。

**当前代码边界：** 代码按 orderNumber 无状态条件更新，pay/cancel 可能都通过，是 P0。

### S11. 为什么支付成功后还需要迁移事件？

**答案：**

订单库事务无法同时提交节目服务 Redis。若订单改成 PAY 后同步 RPC 失败，订单已支付但座位仍 reservation。正确做法是在订单本地事务中同时完成订单 CAS 和 `ReservationTransitionEvent=PENDING`，事务提交后后台以稳定 commandId 调节目服务的幂等 Lua，把 reservation 变为 SOLD；取消同理变为 RELEASED。

事件允许重复执行，最终状态与订单事实收敛。它是 Saga/可靠命令，不是分布式事务。

### S12. 延迟取消为什么不用简单的 ZSET？

**答案：**

单 ZSET 取出并删除后，worker 崩溃会丢任务；先执行再删则多个 worker 可能重复处理。lease queue 用 pending、processing、payload：claim 原子从 pending 移入 processing 并写租约，成功 ACK，失败不 ACK，租约过期 requeue。取消本身仍需订单 CAS 幂等。

完整设计还要处理同 taskId 重复 enqueue、ACK 原子清理所有状态、attempts/DLQ、payload 缺失和长任务续租。当前代码正缺这些边界，所以不能只背“pending/processing 就可靠”。

### S13. Redis Cluster 为什么会 CROSSSLOT？Hash Tag 怎么用？

**答案：**

Cluster 有 16,384 个槽，一个 Lua/事务/多 Key 命令的 Key 必须位于同一槽。Key 中 `{...}` 的内容作为 Hash Tag；例如：

```text
stellaris:{program:100}:seat:owner
stellaris:{program:100}:seat:reservation
```

会同槽。Redis 官方说明多 Key 操作和 Lua 需要同槽，见 R1；所有脚本访问的 Key 也必须通过 KEYS 显式传入，不能把普通参数塞进 KEYS 或脚本内生成动态 Key，见 R2/R3。

**项目回答：** v5 座位和 delay queue 已正确加 tag；worker/DC、captcha 和 legacy Lua 仍不满足 Cluster，不能笼统说“全项目支持 Redis Cluster”。

### S14. 为什么选择令牌桶，不选固定窗口或漏桶？

**答案：**

- 固定窗口实现简单，但边界处可在极短时间通过两倍配额。
- 滑动窗口精确度更高，状态和操作成本也更高。
- 漏桶把输出速率整形成恒定速率，适合保护严格平滑的下游，但会牺牲突发能力。
- 令牌桶以稳定速率补充 token，容量允许合理突发，适合热门开票的查询/下单入口。

星演按查询 IP、下单 USER 配不同容量和失败策略。HTTP 配额拒绝返回 429；依赖故障且 fail-closed 返回 503。Spring Gateway 的限流默认也使用 token bucket 和 429，见 G1；N1/N2 真实问过令牌桶与漏桶。

### S15. Redis 限流器挂了，fail-open、fail-closed、local fallback 怎么选？

**答案：**

按业务风险选：详情查询可以 local fallback，宁可局部不精确也保持可用；下单等会放大库存压力的入口更适合 fail-closed；本地 fallback 必须有最大桶数、空闲淘汰和 overflow 策略，否则 Redis 故障会导致 JVM Map 被高基数 key 打爆。

还要区分“配额用完”和“限流基础设施异常”的指标与状态码。星演已有有界本地桶，但支付真实路径未命中 payment rule，且 USER 头清洗不足。

### S16. Redis 和 MySQL 的库存一致性怎么保证？

**答案：**

先定义权威和阶段：抢票瞬间 Redis owner/reservation 是并发裁决事实，Stream 是短期事件事实；节目数据库座位和订单是持久业务事实。它们不做双写强事务，而是：

1. Lua 原子完成幂等预占与 `XADD`；
2. Stream 消费组至少一次投递 Kafka；
3. Kafka 消费以 reservationId 条件更新数据库 LOCK 并创建唯一订单；
4. 支付取消迁移事件驱动 SOLD/RELEASED；
5. 对账发现长期偏差并按订单事实重放或隔离。

**追问：** 为什么不是延迟双删？延迟双删主要用于缓存副本；这里 Redis 参与短期库存状态机，必须有 reservation、事件重试、终态迁移和对账语义。

**面经依据：** N3/N4/N6/N7/N10 都明确追 Redis/DB 一致性。

### S17. 对账到底对什么？为什么不能只比数量？

**答案：**

数量相等不代表集合相等。一个座位同时在 available 与 sold，另一个座位完全缺失，计数仍可能平衡。至少检查：

- meta = available ∪ owner ∪ sold；
- 三个集合两两不相交；
- reservation 中每个 seat 都由同 intent owner；
- 终态 PAID 对应 sold，CANCEL 对应 released；
- Stream/Dead、Order、PayBill 的 eventId、订单号、金额、版本与状态合法；
- 调度 last success、扫描游标和 finding 持久化。

对账优先报告和安全重放，不能在事实不明确时擅自放座。到期任务只有在源 Stream 事件仍存在、订单不存在且 MySQL 没有该 reservation 锁座时才自动释放；已投递或进入死信的事件保守留给重试/人工收敛。

### S18. 分库分表后 `IN` 查询如何路由？

**答案：**

每个 `IN` 值都要计算目标库表，结果是多个物理节点的去重集合，再执行并归并。只取第一个值会漏数据。ShardingSphere 将 `IN` 视为多分片路由场景，自定义复杂算法必须自己处理全部值，见 H1/H2。

星演当前自定义算法使用 `findFirst()`，而对账一次批量查 500 个订单，这会直接漏订单。修复时还应测试同库同表、同库跨表、跨库同表、跨库跨表四类组合。

### S19. 订单号为什么要带分片基因？雪花算法有哪些坑？

**答案：**

带用户 gene 可以让只拿订单号时仍推导与 userId 相同的库表，减少全路由。代价是 ID 布局与分片拓扑耦合，扩容受预留位数限制。

雪花要处理：时钟回拨、同毫秒 sequence 溢出、workerId 唯一与租约、进程重启、字段位段不重叠。星演当前 `sequence << 6` 占 bit6..17，却仍把 worker 放 bit12..16、datacenter 放 bit17..21，存在碰撞，这是面试前必须修复、不能回避的确定性 bug。

**面经依据：** N11 明确把分布式 ID 和雪花坑列为高频；N4 会继续追分库分表扩容。

### S20. 这个项目目前最真实的三个边界是什么？

**推荐回答：**

> 第一，Docker 是单节点基础设施，验证的是机制，不是生产 HA。第二，系统采用至少一次和最终一致性，不宣称跨 Redis/MySQL/Kafka 的原子事务。第三，当前审计还发现订单号位布局、跨分片 IN、订单终态 CAS、延迟任务状态机等边界，我按业务正确性优先收口，并为每个问题补并发/故障用例。

主动说边界不会减分。真正减分的是 README 写“全解决”，代码却能被面试官几步推导出反例。

---

## 3. A 级：中间件与数据库高频追问

### A01. Kafka 的 producer idempotence 与业务幂等有什么区别？

Producer idempotence 用 PID/sequence 去重同一 producer session 内的重试批次，不能识别应用主动再次发送同一订单，也不能让 MySQL 业务只执行一次；后者必须依靠 eventId/orderNumber、唯一索引和 CAS。K3 也强调应用级 resend 不在 producer 自动去重保障内。

### A02. Kafka 为什么仍可能重复？

业务事务提交后 ACK 前进程退出、consumer rebalance、网络使 ACK 未被 broker 看到、DLT 重放、Stream 中继在 Kafka 成功后 Redis ACK 失败，都可能重复。设计目标应是“允许重复投递，禁止重复业务效果”。

### A03. Kafka 如何保证同一订单消息有序？

用 orderNumber 作为 message key，使同一订单进入同一 partition；Kafka 只保证 partition 内顺序。扩分区后 key 到 partition 的映射可能变化，已有/新消息跨分区；真正的订单状态仍需合法迁移 CAS，不能只依赖到达顺序。

### A04. Consumer Group rebalance 会带来什么问题？

分区所有权转移期间消费暂停；未提交 offset 会在新消费者重放；处理时间超过 poll/心跳限制可能被踢出组。需要控制单批处理时间、合理设置 poll 参数、幂等处理，并监控 rebalance 和 lag。

### A05. Kafka DLT 和 Redis dead stream 有什么区别？

Redis dead stream 是生产中继侧长期无法把事件可靠交给 Kafka；Kafka DLT 是消费侧收到消息但业务处理长期失败。两者的事实、重放入口和幂等键不同，不能共用一个“重新发消息”按钮。重放保留原 eventId/orderNumber，并先验证下游业务是否其实已经成功。

### A06. Redis 为什么快？单线程为什么仍会阻塞？

核心数据在内存，数据结构和命令实现高效，事件驱动减少线程切换；但大 Key、`KEYS`、全量 `HVALS`、长 Lua、慢持久化/网络缓冲都可能让事件循环长时间不能服务其他请求。星演 legacy `programDel.lua` 和全场选座 Lua 正是反例。

### A07. Redis 的 Hash、Set、ZSet 在项目里分别放什么？

- Hash：seat meta、seat owner、intent reservation、Lua 幂等结果；适合 field 定位。
- Set：sold seatId，适合唯一成员与集合校验。
- ZSet：available seats（score=排/列顺序）、延迟任务（score=执行时间/租约）；适合有序范围读取。

回答后要继续说明大 Key 风险和为什么对账的 HGETALL 需要分页替代。

### A08. RDB、AOF 与库存可靠性是什么关系？

RDB 恢复快但可能丢最近快照后的写；AOF 丢失窗口取决于 fsync 策略，重写也需关注。即使开 AOF，Redis 主从故障切换仍不是同步提交，因此该简化方案不能宣称零丢失；需要 PEL/dead、Kafka 幂等、数据库约束、备份和对账共同兜底。

### A09. Redis 分布式锁如何正确实现？Redisson 看门狗解决了什么？

加锁需要唯一 token 与 NX+过期；解锁用 Lua 比较 token 后删除。看门狗在未指定 lease 时续期，降低业务未结束锁先过期；它不能解决进程长 GC 后旧客户端继续写、主从异步复制丢锁或跨资源事务。强业务写可用 fencing token 或数据库 CAS。

### A10. 缓存穿透、击穿、雪崩怎么区分？

- 穿透：查询不存在数据，方案是参数校验、空值短缓存、Bloom Filter。
- 击穿：单个热点失效，大量请求打 DB，方案是互斥重建、逻辑过期、预热。
- 雪崩：大量 Key 同时失效或 Redis 整体故障，方案是随机 TTL、多级缓存、限流降级和 HA。

不要把参与库存状态机的 Redis Key 当作可随意删除重建的普通缓存。

### A11. MySQL 的 ACID 各靠什么实现？

原子性主要依靠 undo log；持久性依靠 redo log 与刷盘策略；隔离性依靠锁与 MVCC；一致性是前三者、约束和业务规则共同得到的结果。真实面经 N2/N3/N5 经常从四特性追到日志和锁。

### A12. InnoDB 默认 RR 如何实现？会不会幻读？

普通一致性读通过 Read View + undo 版本实现可重复读；锁定读/UPDATE/DELETE 根据索引范围使用 record/next-key/gap locks。RR 不是一句“完全没有幻读”就结束，要区分快照读与当前读。官方锁与隔离行为见 M1/M2。

### A13. 为什么订单终态适合乐观 CAS，而不是 `SELECT FOR UPDATE` 包围远程支付？

支付渠道调用耗时不可控，持数据库行锁会占连接、放大等待和死锁风险。短事务先准备账单，事务外调渠道，再用 `WHERE status=NO_PAY` 条件更新终态；失败由查询/回调收敛。悲观锁适合短小的数据库临界区，不应跨远程调用。

### A14. Redis Stream Relay 如何避免消息卡死？

新消息用 Consumer Group 读取；处理中断后记录留在 PEL，超过最小空闲时间由其他消费者 claim；Kafka broker 确认后才 `XACK` 并删除源记录。失败次数必须有上限，达到阈值原子搬到 dead stream；人工重放保留原 eventId/orderNumber。还要监控 PEL、dead 数量和中继失败率。

### A15. 唯一索引为什么是幂等的最后防线？

应用层“先查再插”在并发下两个请求都可能查不到；唯一约束让数据库序列化冲突。捕获 DuplicateKey 后重新读取并校验 payload/owner，才是完整 create-or-get。只吞异常不校验不是幂等。

### A16. 什么时候会死锁？怎么排查？

多个事务以不同顺序持有并等待资源会形成环。例如一个先更新 order 再 ticket，另一个反过来。固定加锁顺序、缩短事务、命中索引、减少范围锁，并对可重试死锁有限重试。通过 MySQL Performance Schema/data_lock_waits、InnoDB status 和业务 traceId 定位。

### A17. 分库分表为什么会让分页、聚合和事务变难？

无分片键查询会全路由；排序/分页要各分片取候选再归并，深分页放大；聚合需二阶段合并；唯一约束只能在单物理表内；本地事务不能跨库。星演用订单号/userId gene 减少单单查询全路由，但 batch IN 和扩容必须正确处理。

### A18. 不停机扩容如何做？“只改配置”可靠吗？

通常需要新旧路由共存、存量回填、增量双写或 binlog 同步、校验、读切换、停止旧写和清理。gene 位只能帮助推导路由，不会自动搬迁数据；如果分片数变化导致 bit 解释变化，历史订单的路由也会改变。N4 会直接追扩容和双写丢失。

### A19. Spring `@Transactional` 为什么会失效？

默认基于 AOP proxy，只有经过代理的外部调用才被拦截；同类 self-invocation 不开启新事务。private/final、异常被吞、checked exception 未配置 rollbackFor、异步线程、错误事务管理器等也会导致预期不符。Spring 官方说明见 S1/S2。

### A20. 远程调用能加入当前 Spring 本地事务吗？

不能。`@Transactional` 常见的 thread-bound transaction 只管理当前服务当前线程绑定的本地资源，不会经 OpenFeign 自动传播到另一个服务，见 S2。跨服务需要 Saga/TCC/消息最终一致等明确协议。

### A21. 线程池七个核心参数怎么结合项目讲？

corePoolSize、maximumPoolSize、keepAlive、unit、workQueue、threadFactory、rejectedExecutionHandler。项目里要从任务类型、下游连接池、延迟目标、队列上界和拒绝策略推导，不按 CPU 核数背一个固定公式。无界队列会让 maximumPoolSize 形同虚设并把过载变成内存/延迟问题。

### A22. `CompletableFuture.supplyAsync()` 不传线程池有什么风险？

默认使用 `ForkJoinPool.commonPool()`；阻塞 RPC/IO 会与其他公共任务竞争，线程命名、隔离、监控和容量都不可控。JDK 官方语义见 J2。核心业务应传入有界、可观测的专用 Executor，并处理超时和异常。

### A23. `volatile` 能保证什么，不能保证什么？

保证可见性和相关有序性；对 volatile 的写 happens-before 后续读，但不提供复合操作的互斥/原子性，例如 `count++` 仍不安全。JDK 并发包的内存一致性规则见 J1。

### A24. `synchronized` 与 `ReentrantLock` 怎么选？

两者都能提供互斥和内存可见性。ReentrantLock 支持可中断、超时 tryLock、公平锁和多个 Condition，但必须 finally 解锁；synchronized 语法简单、JVM 优化成熟。星演的 Redisson/本地锁封装要额外处理中断标志和解锁异常。

### A25. 为什么本地锁 Cache 必须有最大容量？

锁 Key 通常是 orderNumber/requestId，高基数且不断增长。只有 48 小时 expireAfterWrite 仍会在流量高峰保留海量 ReentrantLock。应设置 `maximumSize`、更短的 expireAfterAccess，并确保锁对象仍被线程持有时不会因缓存淘汰产生同 Key 两把本地锁；更稳妥是 striped locks。

### A26. Gateway 为什么不能在 Netty event loop 上直接调用阻塞 Redis？

WebFlux 少量 event-loop 线程负责大量连接；阻塞会拖住同线程的其他请求，形成级联延迟。阻塞客户端要调度到 boundedElastic 或使用 reactive Redis，并设置并发/超时。boundedElastic 也不是无限容量，因此还要舱壁保护。

---

## 4. 支付、退款与状态机专项

### P01. 模拟支付为什么要做成支付策略，而不是测试改状态接口？

因为模拟渠道应复用 PayBill 准备、渠道调用、确认、订单推进、迁移事件和退款链路，只替换外部渠道行为。直接 `/test/setPaid` 只能让页面变绿，无法验证幂等、恢复和跨服务收敛。

### P02. 支付渠道成功、本地确认失败怎么办？

PayBill/订单可能暂时未支付，但渠道是权威成功。通过回调或 `tradeCheck` 按 orderNo 查询渠道，并以合法状态 CAS 收敛 PayBill，再推进订单。查询不能把终态倒退，金额、渠道和商户订单号必须校验。

### P03. 支付回调为什么必须幂等？

渠道会重试回调，网络响应丢失也会导致再次通知。回调签名/金额验证后，用 `NO_PAY -> PAY` CAS；已 PAY 返回成功，其他冲突状态告警/人工处理。不要每次回调都新增流水或重复确认座位。

### P04. 退款为什么先持久化 refundNo 再调渠道？

如果渠道成功后本地事务失败，重试必须用同一个 refundNo 查询/重放，避免渠道生成第二笔退款。稳定幂等号是跨故障窗口的恢复锚点，渠道调用在短事务外。

### P05. 全额退款和部分退款的数据模型有什么区别？

全额退款可一单一 RefundIntent，成功后 PayBill=REFUND。部分退款需要多笔 RefundBill、累计成功金额、每笔唯一 refundNo，以及 PARTIAL_REFUND/REFUND 等状态；不能“允许小于支付金额”却一次把整单标 REFUND。

### P06. 状态机为什么比一堆 if 更可靠？

状态机定义合法边和终态：NO_PAY 可到 PAY/CANCEL，PAY 可到 REFUND，终态不能被迟到消息倒退。数据库 CAS把“当前状态”写进 WHERE，事件 payload 又绑定目标状态。代码 if 只做友好提示，不能作为并发真相。

---

## 5. 现场设计题

### D01. 设计热门演出开票系统

答题骨架：

1. 先问容量、座位/不选座、限购、支付超时、可接受一致性和降级目标。
2. 静态资源 CDN；节目详情多级缓存；网关 IP/USER/节目维度限流。
3. 座位按 program 分区，Redis 热点裁决；Java 有界候选 + Lua 原子预占。
4. 有界 Lua 同时锁座和 XADD；Stream 中继 Kafka，订单/库存幂等。
5. 延迟取消释放；支付/取消可靠迁移命令。
6. Stream dead/Kafka DLT、到期任务、终态对账、指标告警。
7. 热点 program 的单分片极限：拆场次/区域、排级索引、排队令牌、容量预估和快速失败。

### D02. 如果 Redis 也扛不住怎么办？

不要回答“再加 Redis”就结束。先在 CDN/网关削减无效查询，下单使用资格令牌/排队层，把一个节目按区域或票档拆成多个独立热点分区；减少 Lua 指令与 value 大小；读写隔离不适用于强一致预占；对极热点采用单写者/分区队列。任何拆分都要保持“一个座位只有一个裁决者”。N4 真实问过“秒杀千万并发、Redis 也扛不住怎么办”。

### D03. 设计可靠延迟队列

说明任务 ID、dueTime、pending/processing、lease、claim token、ACK、续租、重试次数、退避、DLQ、幂等 handler、数据库兜底和时间源。再指出 Redis ZSET 是演示实现；规模更大可用 Kafka 延时轮、RocketMQ 延时消息或专业调度系统，但终态仍需业务 CAS。

### D04. 设计跨库订单对账

按 `(edit_time,id)` 或状态+时间建立稳定游标；每个逻辑分片独立 checkpoint，批量查询必须让 `IN` 正确路由；finding 持久化并区分可自动修复、只告警和人工处理；终态也要按时间窗复查。任务多实例采用分片分配或 claim lease，不能每个实例全库广播扫描。

### D05. 给出 pay/cancel 并发测试设计

同一 NO_PAY 订单用 barrier 同时发起 pay success 和 cancel；断言订单 CAS 仅一方 affectedRows=1、迁移事件仅一条且 target 与订单终态一致、PayBill/OrderTicketUser/Redis 最终状态匹配。循环执行并随机注入 RPC 延迟；不能只看接口都返回 200。

---

## 6. 反向代码审查题：面试官最可能抓住的点

### C01. “你说雪花 ID 唯一，sequence 为什么左移 6 后还和 worker 位重叠？”

正确态度：直接承认这是静态审计发现的位布局 bug，说明碰撞原理和新的互斥位布局，不要用 `synchronized` 转移话题；同步只保证本实例 sequence 递增，不能修复按位 OR 的信息覆盖。

### C02. “对账传 500 个订单号，分片算法为什么只取 findFirst？”

回答这是自定义 complex algorithm 未实现 IN 多值路由；说明修复为逐值计算目标集合和四类跨片测试。不要说 ShardingSphere 会自动补全，因为自定义算法返回哪些节点就由你的代码负责。

### C03. “取消有锁，支付没锁，两边为什么不会同时成功？”

当前会。正确修复是统一 DB CAS，而不是给 pay 补一把锁就结束。事件在同一事务中跟随 CAS 赢家创建。

### C04. “预热为什么把 SOLD 全改成 NO_SOLD？”

这是把演示重置与线上预热混在一起。拆入口、增加节目未开售/零售出校验，普通预热只重建快照不改业务事实。

### C05. “DLT 的 JSON 都坏了，你第一行就 parse，怎么审计？”

当前无法审计。改成 raw-first、nullable business fields、source position 唯一键和专用 DLT factory。

### C06. “ZSET 用 offset 翻页，前一页元素被抢走后会怎样？”

集合收缩，下一页起点左移，offset 会跳过成员。改用 score+member 游标或按排索引；Lua 仍最终确认。

### C07. “`acks=all`，但你只有一个 broker，all 是几个？”

一个。演示环境验证客户端机制，不具备副本容灾。直接给出生产建议 replication=3、min ISR=2。

### C08. “`@Transactional` 里调 Feign，远端会回滚吗？”

不会。Spring 本地事务不会跨 HTTP 传播。远端成功、本地失败必须依靠幂等命令、补偿/重试和对账处理。

---

## 7. 学习顺序：有限时间先背什么

### 第一轮：必须脱稿

S01-S20、C01-C08。它们完全由你的简历和代码触发，优先级高于偏门 JVM 参数。

### 第二轮：Java 后端一面底座

A01-A20，重点是 Kafka 重复/丢失、Redis 数据结构与锁、MySQL 索引/事务/MVCC、Spring 事务失效。N2/N5/N8 都显示这些是稳定高频。

### 第三轮：加分与设计

A21-A26、P01-P06、D01-D05。每道题都用星演的具体表、Key、状态和故障窗口回答。

## 8. 最终答题原则

1. 先说业务不变量，再说 Redis/Kafka/MySQL。
2. 先说正常状态推进，再说至少三个失败窗口。
3. 分布式锁用于降冲突，数据库 CAS/唯一约束决定真相。
4. Kafka 至少一次，业务效果幂等；不要滥用 Exactly Once。
5. Redis Lua 只有单 Redis/同槽原子性，不是跨存储事务。
6. 对账不是“打印异常日志”，而是有游标、有事实、有 finding、有安全重放边界。
7. 没有测试数字就不报数字；没有多副本就不说高可用。
8. 被指出代码 bug 时，给出反例、根因、修复不变量和验证用例，比强行辩解更加分。
