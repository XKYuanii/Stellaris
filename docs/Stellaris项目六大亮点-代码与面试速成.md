# Stellaris高并发票务项目：六大亮点代码与面试速成

> 目标：用最短时间建立“能从入口讲到异常分支”的项目知识树。不要逐行背代码；每个专题只需要记住入口、核心数据结构、成功链路、失败链路和改进方案。

## 一、先记结论：六条简历与仓库真实实现

| 简历条目 | 仓库结论 | 面试风险 |
|---|---|---|
| Redis + Lua 原子库存 | 核心链路真实存在 | 当前 Redis 是单机配置，Key 没有 Hash Tag，不能说已经支持 Redis Cluster 多 Key Lua |
| Kafka 异步创建订单 | V4/V4-1 链路真实存在 | 报告不支持“吞吐量提升约 26%”；发送端无等待超时，消费失败不会自动重试 |
| 操作流水 + MQ 全生命周期 + 自动对账 | 两套机制都存在，但没有完整串成一条链 | 库存对账定时入口被注释；MQ 生命周期只覆盖延迟取消消息，没有覆盖 Kafka 创建订单 |
| ShardingSphere + 分片基因 | 订单分库分表和基因路由真实存在；支付账单按订单号 Hash 分片 | 支付账单不是按 userId 路由；无分片键会产生广播路由风险；自定义表算法还有一个空条件分支问题 |
| Caffeine + Redis + MySQL 三级缓存 | 节目、票档存在三级缓存和互斥锁 | 座位主要是 Redis + MySQL；代码使用物理过期，不是真正的逻辑过期 |
| Gateway 鉴权、签名、防刷、Sentinel、JMeter | JWT、RSA 验签、Redis + Lua API限制真实存在 | Sentinel 规则持久化配置被注释，配置类缺少明显注册；JMeter 报告是项目方测试，不是仓库测试脚本 |

### 压测数字必须纠正

项目方报告的同配置对比结果：

- V3（Lua + 同步建单 + 本地锁）：79.0 次/秒，平均 253ms。
- V4（Lua + Kafka异步建单 + 本地锁）：91.7 次/秒，平均 86ms。
- 吞吐量提升为 `(91.7 - 79.0) / 79.0 ≈ 16.1%`。
- 117ms 到 86ms 的下降约为 26.5%，但对应 V3-1 与 V4，锁配置不同，不能作为严格单变量对照。

面试若仍然出现“26%”，正确的技术回答应是：

> 复核报告后发现原简历把指标口径写混了。同本地锁配置下，V3 到 V4 的接口吞吐量是 79.0 到 91.7 次/秒，提升约 16%；26% 更接近另一组平均响应时间的下降比例。异步版本改变了接口完成点，所以它反映的是入口同步链路性能，不等于最终订单落库吞吐。

## 二、12 小时极限学习顺序

1. 1小时：背熟项目介绍和完整购票链路。
2. 3小时：读 Redis + Lua 的 4 个文件，背 12 道题。
3. 2小时：读 Kafka 生产、消费和配置，背 15 道题。
4. 2小时：读流水、取消、对账，专练 8 个异常场景。
5. 1.5小时：读分片配置、两个算法类和订单号生成。
6. 1小时：读三级缓存与 Gateway 过滤器。
7. 1.5小时：不看资料完整讲三遍，再做文末自测。

## 三、问题 0：请介绍一下你的项目

### 90 秒标准回答

> 这是一个按微服务拆分的高并发票务系统，核心服务包括 Gateway、节目、订单、支付、用户和自定义治理服务。它要解决的核心问题是热门演出开票时大量用户同时抢同一批座位，既要避免超卖和重复下单，又要缩短同步请求链路，并保证 Redis 库存、订单库和节目库最终一致。
>
> 请求首先经过 Gateway，完成参数签名、JWT 登录态校验和接口访问频率控制；进入节目服务后，先做用户、节目、票档和限购校验，再按节目和票档做细粒度并发控制。真正的库存竞争前置到 Redis：Lua 脚本一次完成余票校验、座位校验或自动配座、扣减余票、座位从未售迁移到锁定，以及库存操作流水写入，从而避免多个 Redis 命令之间被其他请求插入。
>
> V4 下单版本不会在同步线程中等待订单数据库落库，而是预生成订单号，将订单快照和库存流水标识发送到 Kafka。订单服务消费消息后，以订单号做幂等，更新节目数据库库存并写入订单、购票人和流水数据。未支付订单通过延迟消息取消并释放库存；支付成功则把座位从锁定改为已售。项目还设计了库存操作流水对账、消息发送消费记录、订单与支付分片、多级缓存和网关治理机制。
>
> 这套方案的重点不是单点技术，而是把原子扣库存、异步削峰、幂等、补偿和可观测性串到购票链路中。当前代码仍有需要完善的地方，例如 Kafka 创建订单没有接入完整消息对账、库存对账定时入口处于关闭状态、Redis Cluster 的同槽约束还没处理，这些是进一步工程化的方向。

### 完整主链路

```text
客户端
  → Gateway：RSA验签 / JWT解析 / Redis+Lua防刷
  → ProgramOrderController
  → ProgramOrderContext选择V4策略
  → 参数、用户、节目、票档、限购校验
  → @RepeatExecuteLimit防重复下单
  → 节目+票档粒度本地锁
  → Redis Lua：校验库存、锁座、扣库存、写操作流水
  → 预生成订单号
  → Kafka发送create_order，等待生产者回调
  → 接口返回订单号（此时不代表订单已落库）
  → CreateOrderConsumer消费
  → 节目数据库扣库存/锁座
  → 订单库写订单、购票人、订单流水
  → 支付成功：订单改PAY，锁定座位改已售
  → 支付超时：延迟消息取消订单，座位释放、库存回补
```

### 面试官接下来最可能问

1. 为什么 Redis 扣完库存还要更新节目数据库？
2. Redis 成功但 Kafka 失败怎么办？
3. Kafka 消费重复怎么办？
4. 为什么 Lua 能防超卖？
5. 支付成功和超时取消并发怎么办？
6. Redis 与数据库不一致如何发现和恢复？
7. 订单号为什么能用 userId 和 orderNumber 两种条件定向路由？
8. 所谓 26% 是怎么计算的，压测完成点是什么？

---

## 四、亮点 1：Redis + Lua 库存校验、锁座和扣减

### 4.1 必须掌握的代码

按顺序阅读：

1. `stellaris-server/stellaris-program-service/src/main/java/com/stellaris/service/strategy/impl/ProgramOrderV3Strategy.java`
   - `createOrder()`：校验 → 本地锁 → `createNew()`。
2. `stellaris-server/stellaris-program-service/src/main/java/com/stellaris/service/ProgramOrderService.java`
   - `createNew()`。
   - `createOrderOperateProgramCacheResolution()`：预热 Redis、组装脚本参数、执行脚本。
   - `updateProgramCacheDataResolution()`：取消或支付后的反向状态迁移。
3. `stellaris-server/stellaris-program-service/src/main/java/com/stellaris/service/lua/ProgramCacheCreateOrderResolutionOperate.java`
   - 加载 `programDataCreateOrderResolution.lua`，使用 `RedisTemplate.execute()`。
4. `stellaris-server/stellaris-program-service/src/main/resources/lua/programDataCreateOrderResolution.lua`
   - 先完整校验，再统一写入。
   - 手选座位和自动配座两种分支。
   - `HINCRBY` 扣库存、`HDEL` 未售座位、`HMSET` 锁定座位、`HSET` 操作流水。
5. `stellaris-server/stellaris-program-service/src/main/java/com/stellaris/service/SeatService.java`
   - `selectSeatResolution()`：Redis未命中后加锁、二次检查、查MySQL并回填。
6. `stellaris-server/stellaris-program-service/src/main/java/com/stellaris/service/TicketCategoryService.java`
   - `getRedisRemainNumberResolution()`：初始化票档余量。

### 4.2 需要画出来的数据结构

```text
票档余量 Hash
key   = stellaris-program_ticket_remain_number_hash_resolution_{programId}_{ticketCategoryId}
field = ticketCategoryId
value = remainNumber

未售座位 Hash
key   = stellaris-program_seat_no_sold_resolution_hash_{programId}_{ticketCategoryId}
field = seatId
value = SeatVo JSON

锁定座位 Hash
key   = stellaris-program_seat_lock_resolution_hash_{programId}_{ticketCategoryId}
field = seatId
value = SeatVo JSON

库存操作流水 Hash
key   = stellaris-program_record_{programId}
field = reduce_{identifierId}_{userId}
value = ProgramRecord JSON
```

### 4.3 Lua 逻辑必须能口述

```text
1. 判断手动选座还是自动配座。
2. 对所有票档先读取余量，任何一个不足立即返回，暂不修改数据。
3. 手动选座：检查每个座位仍在未售Hash、状态合法、客户端价格不高于服务端价格。
4. 自动配座：从未售Hash读取座位，排序并寻找同排连续座位。
5. 所有校验通过后，统一HINCRBY扣减余量。
6. 把座位从未售Hash删除，写入锁定Hash。
7. 以Redis TIME为时间戳写入本次库存操作流水。
8. 返回业务码和最终锁定座位列表。
```

### 4.4 必背八股与项目问题

#### Q1：为什么要用 Lua，Redis 单命令不是原子的吗？

Redis 的单条命令原子，但“查余量 → 判断 → 扣余量 → 迁移座位 → 写流水”是多步操作。多线程执行多条命令会相互穿插，两个请求可能同时看到库存充足。Lua 在 Redis 内一次执行完整逻辑，其他命令不能插入，保证这组操作的原子性。

#### Q2：Lua 为什么能解决超卖？

脚本在同一个不可插入的执行单元内完成库存判断和扣减。只有读到 `remain >= count` 的请求才会继续；一旦前一个请求完成扣减，后一个请求看到的就是新值，因此不会基于旧库存重复扣减。

#### Q3：为什么先校验全部票档，再开始修改？

一笔订单可能涉及多个票档。若边校验边修改，后面的票档失败时，前面的修改已经发生。Redis Lua 发生运行时错误不会自动回滚已经执行的写命令，所以代码通过“先全量校验、后统一写入”避免业务上的半成功。

#### Q4：手动选座如何避免同一座位被两个人抢到？

脚本从未售座位 Hash 读取指定 seatId。第一个请求成功后会在同一脚本中 `HDEL` 未售Hash并写入锁定Hash；第二个请求再读取时 seatId 已不存在，直接返回座位不可用。

#### Q5：自动配座为什么也放在 Lua 中？

如果 Java 先查询可用座位再单独锁定，查询结果到写入之间存在竞争窗口。项目在脚本中读取、排序、寻找同排连续座位并立即迁移状态，避免选座结果过期。

#### Q6：Lua 脚本越长越好吗？

不是。Redis 执行脚本期间会阻塞其他命令，复杂排序和 `HVALS` 全量读取会增加延迟。自动配座脚本对大座位集合的复杂度较高，生产优化可以用分段座位池、预计算连续座位块、限制单票档座位规模或把匹配结果做候选集。

#### Q7：为什么还保留本地锁？

Lua 已保证正确性，本地锁主要用于单实例内合并同票档竞争，减少大量请求同时打到 Redis 的命令数和网络开销。锁粒度是节目+票档，而不是整个节目；多票档按票档ID排序加锁，并逆序释放，避免不同请求加锁顺序不同造成死锁。

#### Q8：Redis Cluster 下这个 Lua 能直接运行吗？

不能直接承诺。Redis Cluster 要求脚本访问的所有 Key 位于同一个 Hash Slot，通常通过 `{programId}` Hash Tag 保证同槽，而且脚本应只访问通过 `KEYS` 明确传入的键。当前 Key 没有 `{}`，还会通过模板在脚本内动态拼接键；当前配置也是单机 Redis。改造时要统一为例如 `seat:{programId}:ticket:{ticketCategoryId}` 并确保同一订单涉及的Key同槽。

#### Q9：Redis Lua 和 Redis 事务有什么区别？

`MULTI/EXEC` 保证命令批量顺序执行，但事务中的命令通常不能基于前一条返回值做复杂条件分支；Redis事务也不提供传统数据库式回滚。Lua 可以在服务端进行读取、判断、循环和条件写入，更适合库存校验与状态迁移。

#### Q10：Redis 成功后服务宕机怎么办？

库存已经预扣但订单可能没创建。当前链路用库存操作流水、Kafka失败回滚、丢弃订单集合和后台对账作为恢复依据。更稳健的生产方案是使用本地事务消息表/可靠事件表，将“库存预扣事件”持久化，后台按事件状态重投或回滚，并为补偿操作设计幂等键。

#### Q11：库存回补如何保证幂等？

业务上以 `identifierId + recordType` 标识一次状态迁移，并要求只有处于锁定状态的座位才能回到未售，订单状态也必须从未支付条件更新为取消。单纯重复 `HINCRBY +count` 会重复加库存，因此补偿需要先判断流水是否处理过，或者用 Lua 将“检查补偿标记、迁移座位、增加库存、写完成标记”做成一个原子操作。

#### Q12：为什么库存放 Redis，数据库还保留库存？

Redis承担高并发实时竞争，数据库承担最终持久化、查询和对账基准。Redis不是最终事实来源：它可能丢数据、过期或与数据库产生短暂不一致。正常链路先在Redis快速预扣，异步消费者再更新节目数据库；异常通过流水和对账恢复。

### 4.5 一句话亮点回答

> 把库存判断、指定座位校验或自动配座、余票扣减、座位状态迁移和流水写入合并到单次 Redis Lua 执行中，消除了多命令之间的竞争窗口；本地细粒度锁只负责削减 Redis 竞争，正确性由 Lua 保证。

---

## 五、亮点 2：Kafka 异步创建订单

### 5.1 必须掌握的代码

1. `ProgramOrderV4Strategy#createOrder()`：进入异步版本。
2. `ProgramOrderService#createNewAsync()`：Lua 预扣后进入 Kafka 链路。
3. `ProgramOrderService#doCreateV2()`：构建 `OrderCreateMq`、附加 `identifierId`、发消息、发延迟取消消息。
4. `ProgramOrderService#createOrderByMq()`：`CountDownLatch` 等待生产者成功/失败回调；失败时回滚 Redis。
5. `CreateOrderSend#sendMessage()`：`KafkaTemplate.send()` 和 `whenComplete()`。
6. `CreateOrderConsumer#consumerOrderMessage()`：消费消息、检查5秒延迟、调用 `OrderService.createMq()`，失败写入 `DISCARD_ORDER`。
7. `OrderService#createMq()`：订单号幂等、节目数据库库存更新、订单落库、写短期订单缓存。
8. `sql/cloud/stellaris_order_0.sql`：`order_number` 唯一索引是最终幂等兜底。
9. 两个 `application.yml`：生产者 `retries: 1`；消费者自动提交 offset，group为 `create_order_data`。

### 5.2 链路口述

```text
Lua预扣Redis库存
  → 预生成带用户基因的订单号
  → 组装OrderCreateMq（订单快照+购票人+identifierId）
  → KafkaTemplate异步发送
  → 当前线程用CountDownLatch等待生产者回调
      ├─ 发送失败：Redis回滚座位与库存，抛异常
      └─ 发送成功：返回订单号，但订单可能尚未落库
  → 消费者检查消息延迟
      ├─ 超过5秒：写DISCARD_ORDER
      ├─ 创建失败：写DISCARD_ORDER
      └─ 正常：更新节目数据库库存，再创建订单
```

### 5.3 必背 Kafka 八股

#### Q1：为什么异步创建订单？

同步版本需要在请求线程中跨服务更新节目库、写订单主表和明细表，耗时和故障传播都在主链路上。异步版本让同步请求只完成参数校验、Redis预扣和Kafka发送确认，数据库写入由消费者削峰处理，降低接口响应时间并隔离订单服务抖动。

#### Q2：Kafka 的 Topic、Partition、Broker、Consumer Group 是什么？

- Topic 是业务消息类别。
- Partition 是Topic的并行和有序单元。
- Broker 是Kafka服务节点。
- 同一Consumer Group内，一个分区同一时刻只分配给一个消费者；不同组可以各自消费同一消息。

#### Q3：Kafka 为什么吞吐量高？

顺序写磁盘、Page Cache、批量发送、压缩、零拷贝，以及分区并行。不能简单回答“因为完全在内存里”，Kafka核心日志仍然持久化到磁盘。

#### Q4：Kafka 能保证全局有序吗？

只能保证同一分区内有序。若要求同一订单的事件有序，应以 `orderNumber` 作为消息Key，让同订单消息进入同一分区；全局单分区虽然有序，但会牺牲并行度。

#### Q5：生产者 `acks` 有哪些取值？

- `0`：不等Broker确认，延迟最低，最容易丢。
- `1`：Leader写入后确认，Leader故障且Follower未同步时可能丢。
- `all/-1`：等待ISR副本满足条件，可靠性最高但延迟更高。

当前项目没有显式写 `acks`，不能声称代码配置了 `acks=all`。

#### Q6：生产者发送失败怎么办？

当前代码通过发送Future的失败回调回滚Redis库存并抛异常。问题是 `latch.await()` 没有超时，极端情况下会长期占用请求线程；应改为有限超时，超时后把发送状态标记为UNKNOWN，交由可靠事件表查询或重投，不能贸然回滚，因为消息可能已经到达Kafka。

#### Q7：消息已写Kafka，但生产者没收到ACK怎么办？

这是“结果未知”场景。重试可能产生重复消息，直接回滚又可能造成订单消息仍被消费而库存已释放。需要生产者幂等、业务消息ID/订单号幂等、可靠事件状态表和最终对账共同处理。

#### Q8：重复消费怎么处理？

消费者不能假设消息只来一次。项目用 `@RepeatExecuteLimit(orderNumber)` 做业务防重，订单表又对 `order_number` 建唯一索引；更稳妥的实现是在同一个数据库事务中插入消费记录表，`messageId` 唯一，插入成功才执行业务。

#### Q9：消费者创建订单成功，但 offset 提交失败怎么办？

消息会再次投递，因此依赖订单号幂等。第二次消费发现订单已存在或唯一索引冲突后，应把它当成幂等成功，而不是无限报错。

#### Q10：当前消费者失败会自动重试吗？

不会可靠地自动重试。`CreateOrderConsumer` 捕获异常后只写 `DISCARD_ORDER`，没有重新抛出；配置又是自动提交offset。监听方法正常返回后，框架不会把它当作消费失败。当前补救依赖丢弃集合的后续人工或补偿处理，但仓库没有完整自动重放闭环。

#### Q11：如何避免消息丢失？

生产端使用 `acks=all`、合理重试、幂等生产者；Broker设置足够副本和最小ISR；消费端关闭自动提交，业务事务成功后再手动确认；生产业务数据和发消息之间使用Outbox或事务消息；全链路记录messageId并定时对账。

#### Q12：Kafka Exactly Once 能解决业务重复吗？

Kafka事务可以保证Kafka内“消费-处理-再生产”的原子性，但不能天然覆盖MySQL、Redis等外部系统。订单落库仍需要业务幂等、唯一索引或本地消息表。

#### Q13：发生消息积压怎么办？

先看生产速率、消费速率、Lag和失败率，确认瓶颈在DB、RPC还是消费者线程。短期可以增加分区和消费者实例、批量写库、降低非必要逻辑；长期要做容量评估、背压、限流和降级。消费者数量超过分区数不会继续提升并行度。

#### Q14：为什么消息超过5秒要丢弃？

项目把过度延迟视为库存长期锁定风险，将消息放入 `DISCARD_ORDER` 等待补偿。但固定5秒比较激进，生产环境应结合订单有效期、Kafka Lag和消费者处理能力动态设计，且“丢弃”必须和库存释放、订单幂等形成闭环。

#### Q15：V4 返回成功代表什么？

只代表 Redis 预扣成功并且Kafka生产者回调成功，不代表消费者完成订单落库。客户端拿到订单号后可以轮询订单状态；项目还写了1分钟的 `ORDER_MQ` 短缓存辅助查询。接口完成点改变也是异步版本性能更高的重要原因。

### 5.4 当前代码的三个高危点

1. `CountDownLatch.await()` 无超时。
2. 消费者捕获异常不重抛，且自动提交offset，失败消息不会由Kafka正常重投。
3. V2/V21、V3/V31、V4/V41 的 `version` 字符串重复，`ProgramOrderContext` 用Map覆盖，具体注册哪一实现依赖Bean列表顺序。讲压测版本时不要声称七个端点一定精确绑定七个策略。

### 5.5 一句话亮点回答

> V4把订单数据库写入从同步请求链路移到Kafka消费者，主链路只等待消息发送确认；订单号和唯一索引负责重复消费幂等，发送失败回调负责Redis库存回滚，最终订单结果由客户端异步查询。

---

## 六、亮点 3：操作流水、MQ生命周期与自动对账补偿

### 6.1 先分清两套机制

#### A. 库存操作流水对账

对应 Redis 与订单数据库之间的数据一致性：

```text
Lua写 PROGRAM_RECORD
  → 创建订单时把identifierId写进订单和购票人流水
  → ProgramRecordTask记录需要对账的programId
  → OrderTaskService读取Redis流水和DB流水
  → 找出“DB有、Redis没有”的记录
  → 逆向还原before/after库存
  → 删除座位/余票缓存，让下次从DB重建
  → 更新DB对账状态
  → PROGRAM_RECORD迁移到PROGRAM_RECORD_FINISH
```

关键代码：

- `programDataCreateOrderResolution.lua`：写入 `PROGRAM_RECORD`。
- `ProgramOrderService#createProgramRecordTask()`：创建节目对账任务。
- `OrderTaskService#reconciliationTask()`：对比与补偿主逻辑。
- `ProgramRecordHandler#add()`：更新数据库对账状态、迁移Redis流水。
- `order-service/scheduletask/ReconciliationTask.java`：调度入口。

事实边界：`ReconciliationTask` 的 `@Scheduled` 当前被注释，不能说仓库当前会自动定时执行。

#### B. MQ 全生命周期记录

对应延迟取消消息的发送、消费和重试：

```text
发送前插入MessageProducerRecord(UNSENT)
  → 发送延迟消息
  → 更新SEND_SUCCESS或SEND_FAIL
  → 消费前按messageId查询/插入MessageConsumerRecord
  → 更新CONSUMER_SUCCESS或CONSUMER_FAIL及消费次数
  → 每分钟MessageRecordTask对账
  → 未消费/失败消息进入ReconciliationTaskQueue
  → 最多重试3次
  → 发送和消费都成功后标记RECONCILIATION_SUCCESS
```

关键代码：

- `DelayOrderCancelSend.java`。
- `DelayOrderCancelConsumer.java`。
- `MessageProducerRecordService.java`。
- `MessageConsumerRecordService.java`。
- `MessageRecordService#executeReconciliationTask()`。
- `MessageRecordTask.java`。
- `DelayOrderCancelExceptionMessageHandler.java`。
- `MessageType.java`：当前只有 `DELAY_ORDER_CANCEL`。

事实边界：Kafka `create_order` 的 `CreateOrderSend/CreateOrderConsumer` 没有写这套生产、消费记录，所以不能说创建订单Kafka消息已经实现全生命周期自动对账。

### 6.2 必背问题

#### Q1：为什么需要操作流水？

Redis、订单库和节目库无法放进一个本地事务。流水记录每次库存变化的类型、标识、用户、座位、票档、前后数量和状态，使系统能回答“哪一步发生过、缺了哪一步”，为幂等、审计和补偿提供依据。

#### Q2：流水为什么需要 identifierId？

同一订单会经历扣减、支付确认或取消回补。`identifierId` 把这些状态迁移关联到一次库存操作，结合 userId 和 recordType形成业务唯一标识，避免只凭时间或订单状态模糊匹配。

#### Q3：对账以谁为准？

当前 `OrderTaskService` 注释明确写的是“以数据库为准”，查数据库未对账流水，找出数据库存在但Redis缺失的记录，补齐流水并清理相关缓存。生产设计必须先定义事实源，不能Redis和DB互相覆盖而没有优先级。

#### Q4：为什么补偿时删除缓存，而不是直接拼出所有缓存状态？

直接修补座位和余票的多个Redis结构容易再次产生半成功。项目在算出缺失流水后删除相关票档的座位和余票缓存，后续读取通过互斥锁从MySQL重建，降低补偿复杂度。

#### Q5：重试如何防止重复补偿？

重试任务必须用业务唯一键判断是否已完成，数据库状态更新应带前置状态条件，Redis补偿应使用完成标记或Lua原子判断。项目部分依赖对账状态、流水迁移和重复执行限制，但生产级实现还应给消费记录和补偿记录建立唯一索引。

#### Q6：自动补偿和人工补偿如何配合？

可自动判断且幂等的场景自动重试；多次失败、数据矛盾或金额相关场景进入死信/异常表并告警，提供后台查询、人工审核和重放接口。不能无限自动重试，否则会形成重试风暴。

#### Q7：本地消息表/Outbox是什么？

在同一个数据库本地事务中同时写业务数据和待发送事件表；后台扫描事件表投递MQ，成功后更新状态。它解决“数据库提交成功但消息没发出”的原子性缺口，代价是多一张表、扫描任务和状态管理。

#### Q8：最终一致性是不是允许数据永远不一致？

不是。最终一致性要求在允许的时间窗口内，通过重试、补偿或对账收敛到一致状态，同时必须有可观测指标、告警、最大重试和人工兜底。

### 6.3 十个异常场景四句话模板

#### 1. Redis预扣成功，Kafka明确发送失败

- 问题：库存已锁，消息没有进入Kafka。
- 后果：库存泄漏，用户没有订单。
- 当前：失败回调调用缓存反向操作，释放座位并增加余票。
- 改进：回滚也要幂等；增加发送超时、可靠事件表和告警。

#### 2. Kafka发送成功，但生产者没收到确认

- 问题：消息结果未知。
- 后果：重试可能重复，回滚可能造成消息仍消费但库存释放。
- 当前：代码没有完整解决这种不确定状态。
- 改进：幂等生产、订单号幂等、Outbox状态查询和对账。

#### 3. 消费者订单落库成功，但offset提交失败

- 问题：Kafka会再次投递。
- 后果：重复订单和重复扣库存。
- 当前：订单号防重注解和订单号唯一索引兜底。
- 改进：消费记录messageId唯一，并把业务落库与消费记录放同一事务。

#### 4. 消费者创建订单失败

- 问题：Redis已预扣，订单库未完成。
- 后果：库存长期锁定。
- 当前：写入 `DISCARD_ORDER` 和失败指标，但异常被吞掉。
- 改进：关闭自动提交、异常重抛、配置重试/DLT，丢弃订单自动释放库存。

#### 5. 用户重复点击购票

- 问题：相同用户并发发送相同业务请求。
- 后果：多笔订单或重复锁库存。
- 当前：`@RepeatExecuteLimit(userId, programId)` 做入口防重，Lua还会校验座位状态。
- 改进：使用客户端幂等Token或请求号，结果缓存应返回第一次请求结果。

#### 6. 支付回调重复

- 问题：支付平台至少一次通知。
- 后果：重复更新订单和座位。
- 当前：支付账单状态判断、订单状态锁以及条件更新共同防重。
- 改进：回调流水号建唯一索引，状态机只允许NO_PAY→PAY一次。

#### 7. 支付成功与超时取消同时发生

- 问题：两个线程竞争同一订单状态。
- 后果：已付款却释放座位，或者已取消仍记支付成功。
- 当前：按orderNumber使用 `UPDATE_ORDER_STATUS_LOCK`，并校验当前订单状态。
- 改进：数据库使用条件更新 `WHERE status=NO_PAY`，以受影响行数决定胜者；支付已成功但取消先落库时触发退款流程。

#### 8. Redis数据丢失

- 问题：缓存库存和流水缺失。
- 后果：无法判断实时座位状态或可能错误重建。
- 当前：缓存未命中从数据库重建，对账比较DB流水和Redis流水。
- 改进：Redis持久化和高可用、流水落可靠存储、重建期间限流。

#### 9. Redis与MySQL不一致

- 问题：缓存实时态与持久化态不同。
- 后果：错误售票、库存泄漏或用户看见错误座位。
- 当前：库存流水对账、删除异常票档缓存后从DB重建。
- 改进：定义事实源和时间窗口，增加总库存守恒校验与自动告警。

#### 10. 旧任务误删新任务数据

- 问题：延迟任务或补偿任务使用相同Key，旧任务到达时操作了新状态。
- 后果：新订单库存被错误释放。
- 当前：identifierId和订单状态能部分区分操作代次。
- 改进：所有清理和补偿都携带版本号/租约Token，Lua先比较版本再删除，避免ABA问题。

---

## 七、亮点 4：ShardingSphere 订单与支付账单分片

### 7.1 必须掌握的代码

1. `stellaris-server/stellaris-order-service/src/main/resources/shardingsphere-order-local.yaml`
   - 订单：2库，每库4张 `d_order` 表。
   - `order_number,user_id` 为复合分片键。
2. `DatabaseOrderComplexGeneArithmetic.java`
   - 分库：跳过表基因位后取库基因。
3. `TableOrderComplexGeneArithmetic.java`
   - 分表：取低 `log2(tableCount)` 位。
4. `SnowflakeIdGenerator#getOrderNumber(userId)`
   - 固定取 userId 低6位写入订单号低6位。
5. `shardingsphere-pay-local.yaml`
   - 支付账单：2库2表，按 `out_order_no.hashCode()` 的不同位分库分表。
6. `ProgramOrderService#buildCreateOrderParam*()`
   - 调用 `uidGenerator.getOrderNumber(userId)`。

### 7.2 路由算法必须手算

当前订单是2库4表：

```text
tableIndex    = (4 - 1) & shardingKey       // 取低2位
databaseIndex = (2 - 1) & (shardingKey >> 2) // 跳过低2位，再取1位
```

订单号低6位等于userId低6位，因此：

```text
route(userId) == route(orderNumber)
```

例：`userId` 低位二进制为 `...1101`：

- 表索引：`1101 & 0011 = 1`，路由到 `d_order_1`。
- 库索引：`(1101 >> 2) & 1 = 1`，路由到 `ds_1`。
- 订单号低6位复制相同基因，所以按订单号查询也去同一个库表。

### 7.3 必背问题

#### Q1：为什么要分库分表？

单表数据量过大会导致索引层级、查询和维护成本上升；单库连接、CPU、IO和写入能力也有限。分片把数据和请求分摊到多个库表，但会引入跨片查询、事务、分页、扩容和全局ID等复杂度。

#### Q2：为什么同时支持 userId 和 orderNumber 路由？

用户订单列表天然按userId查询，支付、取消和详情常按orderNumber查询。如果两个键的分片结果不同，只有一个条件能定向，另一个会广播。把userId低位基因写入订单号，使两种查询都计算出相同库表。

#### Q3：为什么不用简单 `orderNumber % N`？

订单号本身可以均匀取模，但无法保证与userId路由一致。基因法保留了订单号全局唯一和时间趋势，同时把决定分片的低位强制设置为用户基因。

#### Q4：订单号还是标准雪花ID吗？

它基于雪花结构，但为了预留6位用户基因，对序列位做了左移并覆盖低6位，所以是改造后的雪花ID。必须重新评估位宽、单毫秒序列容量、时间跨度和重复风险。

#### Q5：为什么分库位和分表位要分开？

若库和表都直接 `%2` 使用相同最低位，只会命中部分“库-表”组合。项目低位给表，向右移动表位后再取库位，使2库×4表的8个节点都有机会均匀命中。

#### Q6：没有分片键会怎样？

ShardingSphere通常需要路由到所有实际节点执行再归并，形成读扩散。业务查询必须尽量携带userId或orderNumber；后台跨用户查询可建立ES、归档库或专用映射表。

#### Q7：跨分片分页为什么慢？

每个分片都要执行局部分页或TopN，再在应用端归并排序。页码越深，需要从每个分片取的数据越多。可使用游标翻页、限定用户分片、冗余查询索引或ES。

#### Q8：如何扩容？

修改分片数量不是完整扩容。还需要双写或停机迁移、历史数据重分布、校验、流量切换和回滚。固定6位基因最多表达64个组合，但不同“库×表”布局如何解释这6位仍需稳定版本和迁移策略。

#### Q9：支付账单如何路由？

支付和退款表按 `out_order_no` 的Hash低位分到2库2表。它能按外部订单号定向，但不能直接按userId查询。需要用户维度支付列表时应携带订单号集合、维护映射或使用查询侧存储。

#### Q10：项目分片代码有什么风险？

`TableOrderComplexGeneArithmetic` 在分片值Map为空时返回了空列表，而注释说应返回全部真实表，可能导致无分片键查询行为异常；另外算法假设库表数量是2的幂，因为位与和 `log2` 对其他数量不成立。

### 7.4 一句话亮点回答

> 订单采用2库4表的复合分片，把userId低6位嵌入订单号，使userId和orderNumber共享分片基因；低位用于分表，中间位用于分库，避免两种查询条件造成广播路由。支付账单则按外部订单号独立分到2库2表。

---

## 八、亮点 5：Caffeine + Redis + MySQL 多级缓存

### 8.1 必须掌握的代码

1. `LocalCacheProgram.java`
   - Caffeine `maximumSize`，按演出时间动态过期。
2. `ProgramService#getByIdMultipleCache()`
   - Caffeine → `getById()` → Redis → 互斥锁 → MySQL → Redis。
3. `LocalCacheTicketCategory.java`
   - Caffeine过期时间跟随Redis剩余TTL。
4. `TicketCategoryService#selectTicketCategoryListByProgramIdMultipleCache()`。
5. `SeatService#selectSeatResolution()`
   - 座位先查Redis，未命中后分布式锁、二次检查、MySQL回源并按状态写三个Hash。

### 8.2 真实缓存层级

```text
节目详情：Caffeine → Redis → MySQL
票档列表：Caffeine → Redis → MySQL
座位状态：Redis → MySQL（没有明确的Caffeine座位缓存）
```

代码使用的是到期删除意义上的物理过期，不是“缓存对象携带逻辑过期时间，过期后先返回旧值再异步重建”的标准逻辑过期方案。

### 8.3 必背问题

#### Q1：为什么需要本地缓存？

Caffeine在JVM内访问，无网络开销，适合节目详情和票档等高频、读多写少的数据，能降低Redis网络和CPU压力。代价是多实例间缓存不一致和占用堆内存。

#### Q2：三级缓存查询顺序是什么？

先查Caffeine，未命中查Redis，再未命中通过互斥锁和二次检查回源MySQL，随后写Redis并由Caffeine加载函数保存本地结果。

#### Q3：为什么加锁后还要二次检查？

多个线程第一次都未命中，只有一个获得锁并完成回填。后续线程获得锁时应再次查缓存，若已经存在就直接返回，避免每个等待线程依次查询数据库。

#### Q4：缓存穿透、击穿、雪崩分别是什么？

- 穿透：查询不存在的数据，每次都打到DB。方案：参数校验、空值缓存、布隆过滤器。
- 击穿：单个热点Key失效，大量请求同时回源。方案：互斥锁或逻辑过期。
- 雪崩：大量Key同时失效或Redis故障。方案：过期时间随机化、多级缓存、高可用、限流降级。

#### Q5：互斥锁和逻辑过期如何选择？

互斥锁保证返回较新数据，但未拿到锁的请求会等待；逻辑过期允许返回旧值并异步刷新，可用性和延迟更好，但接受短暂旧数据。票务库存不能靠旧值做最终扣减，详情展示可以接受逻辑过期。

#### Q6：Caffeine常见淘汰/过期策略是什么？

可配置最大容量、写后过期、访问后过期和自定义Expiry。容量淘汰使用近似W-TinyLFU策略兼顾近期性与访问频率。项目使用 `maximumSize` 和自定义 `Expiry`。

#### Q7：如何保证本地缓存、Redis、MySQL一致？

常见Cache Aside：读时逐级回填；写时先更新数据库，再删除Redis和本地缓存。多实例本地缓存删除可通过MQ/Redis Stream广播失效事件，并设置TTL兜底。严格一致很难，必须按数据类型定义允许的不一致窗口。

#### Q8：为什么座位不适合长时间放Caffeine？

座位状态变化频繁且竞争强，本地缓存会让不同实例看到不同状态。项目把座位实时态集中放Redis Hash，MySQL持久化，Caffeine主要缓存相对静态的节目和票档元数据。

#### Q9：缓存空值有什么问题？

能防穿透，但必须设置较短TTL；如果数据随后创建而空值仍存在，会短暂读不到新数据。也要区分“确实不存在”和“下游异常导致的空结果”。

#### Q10：热点Key如何处理？

本地缓存、读副本、Key拆分、请求合并和限流。库存写热点不能简单复制多份，否则要解决多副本扣减一致性；可以按票档或座位段拆分库存，减少单Key竞争。

### 8.4 一句话亮点回答

> 对节目和票档等读多写少数据使用Caffeine、Redis、MySQL三级读取，Redis未命中时通过细粒度分布式互斥锁和双重检查回源；座位实时状态不放长期本地缓存，而以Redis Hash作为竞争层、MySQL作为持久层。

---

## 九、亮点 6：Gateway 鉴权、签名、防刷、Sentinel 与 JMeter

### 9.1 必须掌握的代码

1. `RequestValidationFilter.java`
   - 读取/重写请求体。
   - RSA SHA256验签。
   - Token校验和userId透传。
   - 调用API限制服务。
2. `TokenService.java` 与 `TokenUtil.java`
   - HS256 JWT解析；再从Redis读取登录用户，支持服务端失效登录态。
3. `RsaSignTool.java`
   - 参数排序、排除sign、SHA256withRSA验签。
4. `ApiRestrictService.java`
   - 根据URL、userId/IP和规则组装限流参数。
5. `ApiRestrictCacheOperate.java` 与 `lua/apiLimit.lua`
   - Redis计数、TTL、封禁Key、ZSet时间窗口。
6. `GatewaySentinelConfiguration.java` 与 Gateway `application.yml`
   - Sentinel过滤器/异常处理器概念和Dashboard配置。
7. `RateLimiter.java`
   - 本地Semaphore并发限制；它不是标准令牌桶。

### 9.2 网关链路

```text
请求进入GlobalFilter
  → 生成/透传traceId
  → 读取JSON请求体
  → 按渠道code获取公钥和token密钥
  → 可选RSA解密businessBody
  → 参数排序后SHA256withRSA验签
  → HS256校验JWT的签名和过期时间
  → 用JWT中的userId查询Redis登录态
  → Redis+Lua按URL、用户/IP执行访问规则
  → 把userId、code、traceId写入下游请求头
```

### 9.3 必背问题

#### Q1：JWT由哪三部分组成？

Header、Payload、Signature。Header描述算法，Payload保存Claims，Signature防止内容被篡改。JWT通常只是Base64URL编码，不是加密，敏感信息不能直接放Payload。

#### Q2：项目里的JWT如何实现？

使用HS256共享密钥签名，Subject保存用户JSON，并设置签发和过期时间。Gateway解析userId后再查询Redis登录态，因此不是完全无状态：Redis可以实现退出登录、踢下线和会话失效。

#### Q3：JWT无状态和Redis会话是否矛盾？

不矛盾，是安全与性能的折中。JWT完成完整性和过期校验，Redis保存服务端可控会话。代价是每次请求多一次Redis访问，但可以主动吊销Token。

#### Q4：参数签名和参数加密有什么区别？

签名保证来源可信和内容未被篡改，不保证内容保密；加密保证内容不可读。项目使用客户端私钥签名、网关公钥验签；V2还支持RSA加密业务体。实际传输仍应使用HTTPS。

#### Q5：如何防止请求重放？

仅验签不能防重放，因为攻击者可以复制完整合法请求。应把timestamp、nonce、请求路径和业务体共同签名，Gateway检查时间窗口，并用Redis `SET NX EX` 记录nonce，重复nonce直接拒绝。

#### Q6：Redis Lua防刷是什么算法？

基础规则使用 `INCRBY + EXPIRE`，接近固定时间窗口计数；达到阈值后写一个带TTL的限制Key。深度规则还用ZSet记录触发事件并按时间范围 `ZCOUNT`，具有滑动窗口统计特征。

#### Q7：固定窗口、滑动窗口、漏桶、令牌桶区别？

- 固定窗口：实现简单，边界处可能瞬间放过两倍流量。
- 滑动窗口：更平滑，但状态和计算成本更高。
- 漏桶：恒定速度流出，擅长整形，不适合突发。
- 令牌桶：按速率生成令牌，允许一定突发，网关常用。

#### Q8：限流和熔断有什么区别？

限流保护系统不接受超过能力的流量；熔断在下游错误率或慢调用达到阈值时暂时停止调用，防止级联故障；降级是在资源不足时返回简化结果、缓存或兜底响应。

#### Q9：Sentinel能做什么？

流量控制、并发线程控制、热点参数限流、慢调用/异常比例熔断、系统自适应保护和规则管理。Gateway适配器可以按Route或API分组限流。

#### Q10：当前代码能否声称Sentinel规则已经生产化？

不能。配置中Dashboard连接存在，但Nacos规则数据源被注释；`GatewaySentinelConfiguration` 本身没有明显的 `@Configuration` 注册。可以讲设计和接入点，但要把规则持久化、配置注册和实际压测验证列为完善项。

#### Q11：`RateLimiter` 是每秒限流器吗？

严格说不是。它使用Semaphore控制同时持有许可的数量，释放后许可马上可复用，更接近并发隔离。并且在Reactive链路中 `chain.filter()` 返回后finally会立即释放，不一定覆盖请求真正完成时间。标准QPS限流应使用令牌桶/滑动窗口，Reactive并发控制应在Publisher终止时释放许可。

#### Q12：JMeter需要看哪些指标？

吞吐量、平均响应时间、中位数、P90/P95/P99、错误率、并发用户数、请求总量，同时关联服务器CPU、内存、GC、线程池、数据库连接、Redis命令量和Kafka Lag。只报平均值会掩盖长尾问题。

#### Q13：并发数等于QPS吗？

不等于。并发数是同时在系统中的请求/用户数量；QPS是单位时间完成的请求数。近似关系是 `并发数 ≈ 吞吐量 × 平均响应时间`，还会受思考时间、定时器和压测模型影响。

#### Q14：项目方JMeter测试条件是什么？

100线程，每线程循环40次，共4000请求；提前预热缓存和Feign；同一个用户；关闭限购与重复提交限制；跳过Gateway签名校验；两台个人电脑在同一局域网，Program、Redis和JMeter在13900HX机器，其他服务和Kafka在M4机器。

#### Q15：这份测试能证明生产容量吗？

不能。它是固定环境下的版本对比基准，不是生产容量测试：关闭了部分业务校验、缓存已预热、部署和网络条件特殊、只有单轮结果，而且V4的完成点是Kafka发送确认而非订单最终落库。还应补充多轮均值、P99、错误率、Kafka Lag和最终落库耗时。

### 9.4 一句话亮点回答

> Gateway统一完成渠道参数验签、JWT与Redis登录态校验、用户身份透传，并使用Redis Lua把计数、过期和限制状态原子化；Sentinel负责框架级限流熔断的接入方向，压测则同时观察吞吐、长尾、错误率和下游资源，而不只看平均响应时间。

---

## 十、必须会的综合追问

### Q1：为什么不是直接给数据库库存加乐观锁？

数据库乐观锁能保证不超卖，但所有竞争仍进入数据库，高峰时会产生大量更新冲突、重试和连接占用。Redis把竞争前置并快速失败，数据库异步持久化。最终正确性仍需要数据库条件更新、幂等和对账，不能只信Redis。

### Q2：Redis单线程为什么还能高并发？

核心命令执行主要在内存、数据结构高效、事件循环避免大量线程切换，网络IO在新版本也可多线程处理。单线程执行命令不等于整个Redis进程只有一个线程，也不代表复杂Lua不会阻塞。

### Q3：分布式锁和Lua是什么关系？

分布式锁把临界区扩展到应用层，可保护数据库、RPC等多个资源；Lua只原子化Redis内部操作。项目库存正确性主要由Lua保证，本地/分布式锁更多用于缓存重建或降低竞争。

### Q4：为什么不能追求分布式事务强一致？

下单链路跨Redis、Kafka、节目库、订单库和支付系统，强一致会增加延迟、降低可用性并扩大锁定范围。票务通常采用“入口强校验和原子预扣 + 各服务本地事务 + 幂等消息 + 超时释放 + 对账补偿”的最终一致方案。

### Q5：怎么证明没有超卖？

不能只看接口成功数。压测后要验证：成功锁定座位数不超过初始座位数；同一seatId至多属于一个有效订单；`初始库存 = 未售 + 锁定 + 已售`；数据库与Redis按票档和座位集合对账；所有成功订单都有对应库存扣减流水。

### Q6：什么是状态机，项目里如何使用？

订单只允许合法迁移，例如 `NO_PAY → PAY`、`NO_PAY → CANCEL`，不能从PAY再CANCEL。通过按订单号加锁、数据库条件更新和影响行数判断，解决重复回调以及支付/取消竞争。

### Q7：为什么接口成功但用户暂时查不到订单？

V4返回时消费者可能尚未落库，这是异步一致性窗口。客户端应显示“处理中”，用订单号轮询或SSE/WebSocket接收结果；超过阈值仍未生成则进入补偿和告警。

### Q8：如果Kafka彻底不可用，是否还能售票？

当前异步链路应快速失败并回滚Redis，避免继续锁库存。可降级到同步建单需要谨慎：必须评估订单库容量、设置开关和限流，否则故障时切同步可能把订单库压垮。

---

## 十一、面试时的代码导航清单

### A级：必须能从方法名讲出完整逻辑

1. `ProgramOrderController.java`
2. `ProgramOrderV3Strategy.java`
3. `ProgramOrderV4Strategy.java`
4. `BaseProgramOrder.java`
5. `ProgramOrderService.java`
6. `programDataCreateOrderResolution.lua`
7. `CreateOrderSend.java`
8. `CreateOrderConsumer.java`
9. `OrderService#createMq/cancel/alipayNotify/updateOrderRelatedData`
10. `OrderTaskService#reconciliationTask`
11. `SnowflakeIdGenerator#getOrderNumber`
12. `DatabaseOrderComplexGeneArithmetic.java`
13. `TableOrderComplexGeneArithmetic.java`
14. `ProgramService#getByIdMultipleCache/getById`
15. `RequestValidationFilter#doExecute`

### B级：知道职责和关键数据结构

1. `SeatService.java`
2. `TicketCategoryService.java`
3. `ProgramCacheCreateOrderResolutionOperate.java`
4. `ProgramCacheResolutionOperate.java`
5. `ProgramRecordHandler.java`
6. `DelayOrderCancelSend.java`
7. `DelayOrderCancelConsumer.java`
8. `MessageRecordService.java`
9. `LocalCacheProgram.java`
10. `LocalCacheTicketCategory.java`
11. `TokenService.java`
12. `RsaSignTool.java`
13. `ApiRestrictService.java`
14. `apiLimit.lua`

### C级：只需知道配置结论

1. `shardingsphere-order-local.yaml`：2库4表，订单与购票人相关表复合分片。
2. `shardingsphere-pay-local.yaml`：2库2表，按out_order_no Hash分片。
3. Program `application.yml`：Kafka生产者、单机Redis。
4. Order `application.yml`：消费者组、自动提交offset。
5. Gateway `application.yml`：Sentinel Dashboard、规则数据源注释。

---

## 十二、最后30分钟自测题

不看答案，每题必须在30秒内开口：

1. 用90秒介绍项目。
2. 一次V4购票经过哪些服务？
3. Lua脚本具体操作哪些Key？
4. Redis单命令原子，为什么还要Lua？
5. Lua出错会自动回滚吗？
6. 自动配座为什么不能先在Java查再写Redis？
7. Redis Cluster为什么要求同槽？当前代码支持吗？
8. 本地锁的粒度是什么？多票档为什么排序加锁？
9. Redis成功、Kafka发送失败怎么办？
10. Kafka发送结果未知怎么办？
11. V4接口返回成功具体代表什么？
12. 消费者如何幂等？
13. 当前消费失败为什么不会正常重试？
14. Kafka如何保证不丢消息？
15. Kafka如何保证同订单有序？
16. 消息积压如何排查？
17. 库存流水记录哪些内容？
18. 库存对账以谁为准？
19. MQ全生命周期当前覆盖哪类消息？
20. 支付成功和取消同时发生怎么办？
21. 订单号的低6位是什么？
22. 2库4表如何从ID算库表下标？
23. 为什么userId和orderNumber都能定向？
24. 支付账单按什么分片？
25. 没有分片键会发生什么？
26. 节目、票档、座位分别有几级缓存？
27. 缓存穿透、击穿、雪崩的区别？
28. 项目代码是真逻辑过期吗？
29. JWT、RSA签名、HTTPS分别解决什么问题？
30. Redis Lua防刷属于什么限流算法？
31. Sentinel限流、熔断、降级的区别？
32. 并发数、QPS、响应时间是什么关系？
33. 项目方压测关闭了哪些真实业务条件？
34. V3到V4的真实吞吐提升是多少？
35. 为什么异步版的压测不是完全等价的端到端比较？

## 十三、最小背诵卡片

如果只剩1小时，只背下面15句：

1. Lua把余票校验、锁座、扣库存、座位迁移和写流水合并成一次Redis执行。
2. Redis单命令原子不等于多命令业务原子；脚本先全校验再写，避免半成功。
3. 本地锁按节目+票档削减Redis竞争，多票档排序加锁、逆序释放防死锁。
4. 当前Key没有Hash Tag且脚本动态拼Key，只能按单机Redis实现解释。
5. V4接口只等Kafka生产者回调，不等订单消费者落库。
6. Kafka发送明确失败会回滚Redis；结果未知要靠幂等、事件表和对账，不能盲目回滚。
7. 订单消费者用orderNumber防重，数据库唯一索引最终兜底。
8. 当前消费者吞异常且自动提交offset，创建订单失败重放闭环不完整。
9. 库存流水和MQ生命周期是两套机制：前者对Redis/DB，后者当前只覆盖延迟取消消息。
10. 库存对账定时入口当前被注释，代码有能力但默认未自动运行。
11. 订单号低6位复制userId低6位，低2位分表、再取1位分库。
12. 节目和票档是Caffeine+Redis+MySQL；座位主要是Redis+MySQL。
13. 项目使用互斥锁和物理过期，没有实现标准“返回旧值+异步重建”的逻辑过期。
14. Gateway用HS256 JWT+Redis登录态、SHA256withRSA验签、Redis Lua接口规则。
15. 报告中V3到V4吞吐是79.0到91.7，提升约16%，且完成点由落库变成消息发送确认。
