# 星演票务系统全仓静态代码审计

> 状态更新（2026-08-26）：本文保留的是修改前基线，文中的 P0/P1 不能继续直接代表当前代码。逐项核销、验证结果和仍保留边界见 [20260826_REMEDIATION_RESULT.md](20260826_REMEDIATION_RESULT.md)。

审计日期：2026-08-25  
审计方式：只读静态审阅。本轮未启动 Docker、未启动服务、未执行单元测试、接口测试、前端测试、并发测试或故障注入。

## 1. 最终结论

这不是一个“只搭了组件、主链没有接通”的项目了。v5 主链已经实际接入了订单意图、Redis Lua 预占、本地 Outbox、Kafka 手动提交与重试/DLT、订单幂等创建、支付/取消可靠迁移事件、延迟取消、退款 Intent、对账调度和 Prometheus 告警。

但是现在仍不能对外宣称“零丢失、Exactly Once、生产级强一致、全链路已经闭环”。静态审阅发现 6 组会直接影响业务正确性的 P0 问题，其中订单号位段重叠、分片 `IN` 路由错误、支付/取消竞争、预热重置已售库存最严重。这些不是安全加固，也不是编码风格问题，而是面试官能够沿着数据流直接推导出的业务漏洞。

更准确的定位是：

> 星演是一个围绕热门演出票务交易构建的工程化演示系统。项目已经形成可靠事件、至少一次投递、业务幂等、恢复任务、三层对账和可观测性骨架，但在分布式 ID、跨分片批量查询、终态 CAS、延迟队列状态机和库存初始化边界上仍需收口。

### 评分

| 维度 | 静态评分 | 结论 |
|---|---:|---|
| 架构方向 | 8/10 | Intent + Lua + Outbox + Kafka + 对账的方向正确 |
| 单路径可演示性 | 7.5/10 | 代码链路已接通；本轮未运行，不能据此证明当前环境可执行 |
| 并发正确性 | 5/10 | ID、支付/取消、延迟任务、分片 `IN` 存在确定性漏洞 |
| 故障恢复闭环 | 6/10 | 有恢复组件，但终态扫描、毒消息和永久失败策略不完整 |
| 可观测性 | 6.5/10 | 有指标和告警，缺持久化对账报告与若干真实状态校验 |
| 工程可维护性 | 5.5/10 | 模块清楚，但遗留版本过多、巨型 Service、循环依赖和手工 SQL 明显 |
| 生产工程化 | 4.5/10 | 单节点依赖、无迁移工具、缺 HA 和完整异常证据；本项目本来也不是生产部署目标 |
| 面试可讲性 | 7/10 | 亮点足够，但必须主动说清边界；修完 P0 后可明显提升 |

## 2. 审阅覆盖范围

本轮建立文件清单后读取了 972 个源码与运行配置文件，共约 78,693 行：

| 类型 | 数量 |
|---|---:|
| Java | 765 |
| XML | 71 |
| Vue | 32 |
| JavaScript | 28 |
| SQL | 23 |
| Lua | 18 |
| YML | 18 |
| YAML | 10 |
| JSON | 2 |
| SCSS | 2 |
| HTML / properties / PowerShell | 各 1 |

排除范围：`target`、`node_modules`、`dist`、日志、输出目录、临时目录、IDE 元数据、Git 元数据、自动生成的 `.flattened-pom.xml` 和文档渲染产物。所有 Maven `pom.xml` 另行检查过依赖与编译配置。

审阅分为两层：

1. 所有文件执行原文读取和结构/契约检查，检查包依赖、DTO/VO/Entity 字段、Mapper 与 SQL、配置绑定、异常处理、事务/调度/监听器声明、Redis Key 和脚本调用关系。
2. 交易主链及高风险文件逐段语义深审，包括网关限流、v5 下单编排、18 个 Lua、Intent/Outbox、Kafka、订单创建、支付退款、取消、延迟队列、分片算法、对账、SQL 和前端支付页面。

普通 DTO、VO、枚举、Mapper、Feign Client 和启动类按跨层契约分组评价，不为每一个无独立逻辑的文件重复写“无问题”。出现字段、路由或状态语义问题时，已归入对应业务问题。

### 模块覆盖

| 模块 | 文件数 | 审阅意见 |
|---|---:|---|
| `stellaris-benchmark` | 2 | 仅有基础压测入口/模型，不能支撑 README 中的性能结论 |
| `stellaris-captcha-manage-framework` | 41 | 第三方风格工具代码较重，存在 `printStackTrace`；不是交易核心 |
| `stellaris-common` | 61 | 公共枚举/工具契约基本完整，仍有演示 `main`、控制台输出和超大工具类 |
| `stellaris-elasticsearch-framework` | 9 | 搜索封装可用，异常与输出方式偏演示化 |
| `stellaris-id-generator-framework` | 37 | 存在 P0 位段冲突和 worker/DC 分配复用问题 |
| `stellaris-redis-tool-framework` | 18 | API 很全但类过大；需要避免将阻塞调用带入响应式线程 |
| `stellaris-redisson-framework` | 57 | 锁抽象齐全；本地锁缓存无上限、线程中断丢失 |
| `stellaris-server` | 407 | 主业务链已接通，P0/P1 主要集中于 order/program/pay/gateway |
| `stellaris-server-client` | 177 | DTO/VO/Feign 契约总体匹配；版本与状态类型仍大量使用裸整数/字符串 |
| `stellaris-spring-cloud-framework` | 63 | 灰度/初始化/分片框架齐全；自定义复杂分片算法有 P0 |
| `stellaris-thread-pool-framework` | 8 | 基础封装可用，缺少与核心调度任务的统一容量治理证据 |
| `ops` | 4 | Docker/Prometheus 可用于面试演示；全部基础设施是单节点 |
| `sql` | 22 | 新建库脚本较全；升级脚本仍为手工、非幂等流程 |
| `vue3` | 64 | v5 和模拟支付已接入；路由状态、轮询和终态展示仍有缺口 |
| 根 `pom.xml` / Spotless | 2 | Java 17 明确；依赖属性重复、插件较旧 |

## 3. v5 端到端链路复原

```text
前端 createV5
  -> Gateway 令牌桶 / 并发舱壁
  -> Program 参数与购票人校验
  -> MySQL 创建 OrderIntent(INIT)
  -> 生成订单号、座位快照和完整订单消息
  -> MySQL CAS: INIT -> RESERVING（锁座前已可恢复）
  -> Redis Lua 原子预占座位
  -> MySQL 本地事务: Intent -> RESERVED + Outbox(PENDING)
  -> Outbox Relay -> Kafka
  -> Order Consumer 手动 ACK
  -> 节目库库存 LOCK 幂等落库
  -> 订单库创建 Order / OrderTicketUser
  -> Redis 延迟取消任务
  -> 支付或取消 CAS（当前这里尚未真正 CAS）
  -> 订单本地事务写 ReservationTransitionEvent
  -> 节目服务 Lua 确认 SOLD 或 RELEASED
  -> 对账调度扫描 Intent / Outbox / Order / Pay / Redis
```

### 已经真正完成的部分

- `ReferenceOrderOrchestrator` 在 Redis 锁座前先把订单号、服务端座位快照和完整订单消息写入 MySQL Intent。最初的“Lua 成功到可靠事件落库之间 kill -9”窗口已经转化为可恢复的 `RESERVING` Intent。
- Redis v5 座位 Key 统一使用 `{program:id}` Hash Tag；预占、确认售出和释放脚本具备单节目原子性。
- Intent 转 `RESERVED` 与 Outbox 插入位于同一个 MySQL 本地事务，Kafka 不被错误地包含进本地事务。
- 生产者配置了 `acks=all` 和幂等生产；消费者关闭自动提交、使用 `MANUAL_IMMEDIATE`，失败进入重试与 DLT。
- 创建订单、节目库存操作、退款调用和迁移命令都引入了业务幂等键或状态。
- 支付/取消后不再只依赖同步 RPC，而是写订单本地 `reservation_transition_event` 并后台重试。
- 延迟取消采用 pending/processing/ACK 的 lease 思路，并有 MySQL 过期订单扫描兜底。
- 对账调度和 Prometheus 告警默认启用，已经不是只有文档没有执行入口。

### 尚未闭环的关键点

- 数据库订单终态没有统一的条件更新，支付和取消可能各自成功。
- 订单号本身可能碰撞；一旦碰撞，所有“订单号唯一幂等”设计都会失去前提。
- 跨分片 `IN` 查询漏路由，使对账看不到部分订单。
- 延迟队列同一 taskId 可以同时位于 pending 和 processing，ACK 后留下无 payload 的毒任务。
- 预热入口可把已售座位和余票恢复成未售状态。
- 毒 Kafka 消息可能无法落 DLT 审计，并进一步投递到 `.DLT.DLT`。
- 终态 Intent 不再进入对账扫描，支付/取消后的长期不一致可能被遗漏。

## 4. P0：必须先修的业务正确性问题

### P0-1 订单号位段重叠，存在同毫秒碰撞

文件：`stellaris-id-generator-framework/src/main/java/com/stellaris/toolkit/SnowflakeIdGenerator.java`

普通 `nextId()` 使用标准布局：12 位 sequence、5 位 worker、5 位 datacenter。`getOrderNumber(userId)` 为预留 6 位分片基因，把 `sequence` 左移 6 位，但仍把 worker 放在 bit 12、datacenter 放在 bit 17：

```text
sequence << 6 : bit 6..17
worker   << 12: bit 12..16
dc       << 17: bit 17..21
```

sequence 与 worker/datacenter 重叠，按位 OR 后不同 sequence 可得到相同结果。同用户在同一毫秒内的多笔订单可能触发唯一键冲突；更严重的是，订单号又是库存、Outbox、支付、退款、迁移事件的幂等键，碰撞会把两笔业务误认为同一笔。

修复方案：重新设计 64 位布局，所有字段必须互不重叠。可选方案：

1. 明确压缩时间/节点/序列位，单独保留 6 位 route gene；启动时校验总位数不超过 63。
2. 不把 gene 塞进雪花 ID，订单表额外保存 `route_key/user_id`，查询按 route key 路由。
3. 如果保留 gene，分片扩容方案也必须与位宽绑定，不能宣传“只改 YAML 即可任意扩容”。

同时修复 `workAndDataCenterId.lua`：当前 1024 个组合用完后直接回绕，老实例尚存时会复用节点号；两个 Key 也没有共同 Hash Tag，在 Redis Cluster 会 CROSSSLOT。应使用带租约的实例注册，或采用数据库/注册中心持久节点分配。

### P0-2 自定义分片算法对 `IN` 只取第一个值

文件：

- `stellaris-spring-cloud-framework/stellaris-service-common/src/main/java/com/stellaris/shardingsphere/DatabaseOrderComplexGeneArithmetic.java`
- `stellaris-spring-cloud-framework/stellaris-service-common/src/main/java/com/stellaris/shardingsphere/TableOrderComplexGeneArithmetic.java`
- `stellaris-server/stellaris-order-service/src/main/java/com/stellaris/service/reference/ReferenceOrderStateQueryService.java`

两个 `ComplexKeysShardingAlgorithm` 都对分片值集合调用 `findFirst()`。因此：

```sql
WHERE order_number IN (A, B, C)
```

只会路由 A 所在的库表。如果 B/C 在其他分片，它们会被静默漏掉。当前三层对账一次最多用 500 个订单号执行 `IN`，所以会错误地报告“订单不存在”，或者完全看不到真实订单。

修复方案：对集合中每个值计算物理库/表并取去重集合；数据库与表算法都要支持多值。初始化时校验分片数是 2 的幂且库位 + 表位不超过 gene 位。物理库匹配不能使用 `contains(index)`，否则扩容到 `ds_10` 后 `ds_1` 可能误匹配，应解析精确后缀或直接构造名称。

### P0-3 支付与取消没有由 MySQL CAS 决定唯一赢家

文件：

- `stellaris-server/stellaris-order-service/src/main/java/com/stellaris/service/OrderService.java`
- `stellaris-server/stellaris-order-service/src/main/java/com/stellaris/service/reference/ReservationTransitionEventService.java`

`cancel()` 有分布式锁，`pay()` 没有使用同一把订单锁。更关键的是 `updateOrderRelatedData()` 先读 `NO_PAY`，再按 `order_number` 更新，没有：

```sql
WHERE order_number = ? AND order_status = NO_PAY
```

支付和取消并发时，两边都可能通过前置检查，最后写入者覆盖前者。随后 `ReservationTransitionEventService.enqueue()` 对同一订单只保留一条事件；如果已有事件，直接返回，不校验目标是 SOLD 还是 RELEASED。最终可能出现：

- Order=PAY，但迁移事件要求释放座位；
- Order=CANCEL，但迁移事件要求确认售出；
- PayBill=PAY，而订单和 Redis 座位终态不一致。

修复方案：本地事务内执行 `NO_PAY -> PAY` 或 `NO_PAY -> CANCEL` 的条件更新，`affectedRows == 1` 的一方才是赢家，并且只有赢家可以创建对应迁移事件。已存在事件必须校验 `targetSellStatus`、intentId、programId 和 payload 一致。分布式锁只能降冲突，数据库 CAS 才是真相。

### P0-4 普通预热会重置已售座位

文件：

- `stellaris-server/stellaris-program-service/src/main/java/com/stellaris/service/ProgramService.java`
- `stellaris-server/stellaris-program-service/src/main/java/com/stellaris/service/reference/ReferenceSeatInventoryService.java`

`dataPreheat()` 虽先原子关闸并拒绝活跃 reservation，但随后调用 `resetExecute()`：只要发现 LOCK 或 SOLD，就把该节目所有座位重置为 `NO_SOLD`，并把票档余票恢复为总票数。之后再从数据库快照构建 Redis。因此售出过的节目执行预热会重新出售已支付座位。

代码注释写了“只能开售前调用”，但没有节目状态、开售时间、已支付订单数或 SOLD 事实校验，注释不是不变量。

修复方案：

- 将“开售前初始化库存”和“演示环境重置数据”拆成两个入口；
- 初始化必须校验节目未开售、无已支付/已售事实；
- 普通预热只从数据库权威状态构造新版本快照，绝不把 SOLD 改回 NO_SOLD；
- 使用版本化 Key 构建完成后原子切换 ready/version，旧版本延迟清理。

### P0-5 延迟取消任务可同时处于 pending 和 processing

文件：

- `stellaris-server/stellaris-order-service/src/main/java/com/stellaris/service/delaylease/DelayCancelLeaseQueue.java`
- `stellaris-server/stellaris-order-service/src/main/java/com/stellaris/service/delaylease/DelayCancelLeaseWorker.java`
- `delayEnqueue.lua`、`delayClaim.lua`、`delayAck.lua`、`delayRequeueExpired.lua`

数据库兜底扫描可在 worker 正处理同一 `taskId` 时再次执行 enqueue。当前 enqueue 无条件 `HSET payload + ZADD pending`，不会检查 processing。处理成功后 ACK 只删除 processing 和 payload，不删除 pending，于是 pending 留下一个没有 payload 的任务。下一次 claim 取得它后解析失败，永远不 ACK，成为毒任务。

另外还存在：固定 30 秒 lease 无心跳；取消 RPC 超过 lease 时会被其他节点重领；不存在的订单也可能永久重试；没有 attempts、最大重试和 dead queue。

修复方案：把 enqueue/claim/ack/requeue 统一成一个可证明的任务状态机。enqueue 仅在 pending/processing 都不存在时创建，或引入版本 token；ACK 必须原子删除 pending、processing、payload；任务保存 attempts/lastError，超过阈值进入 dead ZSET/Hash；长任务增加 lease 续租或把 lease 设置为有上界的业务超时。

### P0-6 DLT 审计处理不了最需要审计的毒消息

文件：

- `stellaris-server/stellaris-order-service/src/main/java/com/stellaris/config/KafkaConsumerReliabilityConfiguration.java`
- `stellaris-server/stellaris-order-service/src/main/java/com/stellaris/service/kafka/CreateOrderDltConsumer.java`
- `stellaris-server/stellaris-order-service/src/main/java/com/stellaris/service/kafka/OrderCreateDltService.java`
- `sql/reliability/20260825_order_create_dlt_audit.sql`

DLT listener 使用默认 container factory，而默认 error handler 会把失败记录发送到 `record.topic() + ".DLT"`。如果 DLT 审计自身失败，消息可能进入 `.DLT.DLT`。

`record()` 在保存原始 payload 之前先反序列化 `OrderCreateMq`。格式错误正是典型毒消息，此时无法写审计表；审计表又要求 `order_number NOT NULL`，进一步阻止“先保存原文、后尽力解析”。

修复方案：为 DLT 审计建立专用 container factory，不再二次发布 DLT；第一步按 `sourceTopic + partition + offset` 唯一保存原始 payload、headers 和异常，业务字段允许为空，第二步再 best-effort 解析。审计保存失败时保持 offset 未提交并告警，而不是制造 DLT 链。

## 5. P1：链路完整性和工程化不足

### P1-1 三层对账只扫描非终态 Intent

`ReferenceReconciliationExecutor.selectNextIntentPage()` 只选择 INIT、RESERVING、RESERVED、ORDER_CREATING、ORDER_CREATED、CANCELLING、COMPENSATING。PAID、CANCELLED、FAILED、COMPENSATED 不再检查，因此支付后订单/PayBill/Redis SOLD 不一致、取消后座位未释放等长期错误会被跳过。

库存校验也只从本批 Intent 推导节目；只有终态 Intent 的节目不会进入检查。当前不变量只比较：

```text
meta count == available count + owner count + sold count
```

重复座位与缺失座位可能相互抵消；没有验证 available/owner/sold 集合两两不相交、完整覆盖、reservation 与 owner 一致、ready/maintenance/version 状态。

建议新增终态增量游标、节目维度轮转游标、集合级不变量与持久化 `reconciliation_run/finding`。自动重放 DEAD 应在查询订单事实后执行，避免订单已存在仍重复投递。

### P1-2 创建订单在节目库 LOCK 与订单库提交之间仍有 Saga 缺口

Kafka 消费者先调用节目服务把数据库座位变成 LOCK，再在订单库创建订单。若订单创建永久失败并进入 DLT，节目数据库座位会保持 LOCK。Intent/Outbox 能保存事实，对账也会报告，但目前没有明确的“证明订单不存在后释放”终态策略和人工隔离状态。

这不是消息丢失，而是跨服务 Saga 未完成。应定义：重放上限、订单事实查询、确认不可恢复后的补偿释放、UNKNOWN 人工状态，以及补偿命令的稳定幂等键。

### P1-3 支付查询可把终态倒退，退款模型与部分退款语义冲突

`PayService.tradeCheck()` 在 `@Transactional` 方法里调用外部渠道，网络等待会占用事务资源。它又无合法状态迁移校验地把渠道状态覆盖到 PayBill；迟到的 `WAIT_BUYER_PAY`/CLOSED 结果可能把 PAY/REFUND 倒退。

退款允许 `amount <= payAmount`，但一旦成功就把整张 PayBill 标成 REFUND；每订单又只有一个 RefundIntent/RefundBill 唯一记录，因此实际不支持多次部分退款。要么明确只支持全额退款并要求金额相等，要么引入累计退款金额和多退款单模型。

`completeRefund()` 更新 PayBill 没有 `PAY -> REFUND` CAS；重试任务无最大次数和 nextRetryTime 指数退避，会每分钟重试永久失败记录。`lastError` 也应按数据库长度截断。

### P1-4 v5 业务准入规则没有真正生效

`ProgramUserExistCheckHandler` 中“单账号累计限购”整段被注释。订单创建/取消对 Redis 计数的写入还位于数据库事务中，数据库回滚不会回滚 Redis，计数会漂移。当前应删除虚假的规则表述，或将限购作为独立可靠计数/数据库事实实现。

`ProgramDetailCheckHandler` 只检查单笔数量和是否允许选座，未发现明确的开售时间、停售时间、节目状态准入；`permitChooseSeat=NO` 时只要 `seatDtoList != null` 就拒绝，空数组也被误判。

### P1-5 自动选座分页在并发删除下会跳项

`ReferenceSeatInventoryService.findCandidates()` 使用 ZSET 的 offset 分页。并发锁座会 `ZREM`，后续 offset 对应的成员向前移动，导致跳过候选座位，出现“实际有连续座但返回无座”。应使用 `(score, member)` 游标分页，或按排建立独立索引。`candidate-limit=2000` 是可以接受的有界退化，但必须对面试官说明会产生可控的假阴性。

`referenceReleaseSeats.lua` 在缺少某票档 available Key 参数时仍删除 owner/reservation 并写 RELEASED，会丢失可售座位。应在任何写操作前验证所有票档 Key 都存在，否则整体返回错误。

### P1-6 Redis Cluster 只有 v5 主链修对了 Hash Slot

18 个 Lua 均已逐个检查：

| 脚本组 | 结论 |
|---|---|
| `referenceReserveSeats/ConfirmSale/ReleaseSeats/BeginPreheat/FinishPreheat` | Key 统一 `{program:id}`，Cluster 槽位正确；释放脚本有缺票档 Key 静默丢座问题 |
| `delayEnqueue/Claim/Ack/RequeueExpired` | Key 统一 `{delay:cancel}`，槽位正确；任务状态机不完整 |
| `tokenBucket.lua` | 单 Key，无 CROSSSLOT；使用网关节点时间，节点时钟漂移会影响全局精度 |
| `workAndDataCenterId.lua` | 两个 Key 无共同 Hash Tag，Cluster 下 CROSSSLOT；节点 ID 还会回绕复用 |
| `checkNeedCaptcha.lua` | counter、timestamp、captchaId 三个 Key 无共同 Hash Tag，Cluster 下 CROSSSLOT |
| `apiLimit.lua` | 把 JSON 规则放进 `KEYS[1]`，并访问未显式声明的动态 Key，不符合 Cluster 脚本约束 |
| `programDataCreateOrderResolution.lua` | 遗留版本读取/解析/排序大批座位，复杂度高且动态 Key 模型不适合 Cluster |
| `programDel.lua` | Lua 内执行 `KEYS pattern`，会阻塞 Redis 且 Cluster 只看单节点 |
| 其他 legacy program/order Lua | 可用于版本对照，不应作为 v5 或生产级能力宣传 |

限流可改用 Redis 服务端时间或统一时钟；遗留脚本应标注 legacy，仅 v5 作为参考实现。

### P1-7 网关限流规则没有覆盖真实支付入口

网关配置的支付规则是 `/stellaris/pay/**`，但前端支付经过订单服务 `/stellaris/order/order/pay` 和 `/pay/check`，因此核心支付入口没有命中 payment-user 规则。

`RequestValidationFilter` 复制原始请求头后只覆盖成功推导的 userId/code；如果未推导出值，客户端传入的旧 `userId/code` 仍保留。这会让 USER 维度限流被伪造/轮换，即使不讨论安全，也会破坏限流业务语义。进入网关时应先删除受信头，再写入网关验证结果。

正面点：令牌桶能区分 429 配额拒绝和 Redis 故障降级；本地 fallback 有最大 10,000 桶、空闲回收和 overflow 桶；阻塞 Redis 调用被移到 boundedElastic。仍需把配置校验前置到启动期，并把 `stellaris.gateway.*` 指标统一为 `stellaris.gateway.*`。

### P1-8 Kafka 配置正确，但单节点使 `acks=all` 没有副本意义

order/program producer 已配置 `acks=all + enable.idempotence=true`，order consumer 已配置 manual ack、重试和 DLT。代码层机制是正确的。

但是 `ops/docker-compose.interview.yml` 只有一个 Kafka broker，主题默认 replication factor=1、min ISR=1。此时 `acks=all` 只等同于单副本落盘确认，无法承受 broker/磁盘损坏。面试表述应为“演示环境单节点；生产拓扑建议 3 副本、min ISR=2”，不能说当前部署已高可用。

### P1-9 Outbox/迁移 relay 可用但扫描成本与状态反馈不严谨

多个调度任务在分片表上缺少分片键，会广播扫描；多实例都执行，再靠 CAS 抢占，功能上可容错，数据库成本会随分片/实例数放大。应按逻辑分片分配任务或引入调度分片。

`OrderCreateEventPublisher.markSent/markFailed()` 忽略 update affected rows，却直接修改内存状态和指标；竞争时指标可能显示成功而数据库状态未更新。迁移事件的 success/failure 更新也应检查结果。

## 6. P2：维护性、前端和表述问题

### 前端

- `payMethod.vue` 只从 `history.state.orderNumber` 取订单号。刷新或直接访问时得到 undefined，并可能写入 localStorage；应同时从 route query/localStorage 恢复并验证订单详情响应。
- 后端已有支付账单后不允许切换 channel，但页面仍允许从 mock 切换 alipay，交互语义不一致。
- 支付宝使用 `document.write()` 替换整页，适合演示，不宜称为成熟前端集成。
- `paySuccess.vue` 把除 PAY 外所有订单状态都映射成 PENDING，取消、退款、失败不能正确展示。
- 订单创建页每 200ms 发一次查询，未等待上一次 promise 完成，慢请求时会重叠。虽然 `onBeforeUnmount` 已清理 timer，但仍应改为一次请求结束后再调度下一次并使用退避。
- Axios 请求拦截器失败分支没有 `return Promise.reject(error)`，调用链可能收到 undefined。
- 座位页面为了画梯形对真实座位数组切片，可能使合法座位不可选择；展示布局不能改变业务集合。

### Java/框架

- `LocalLockCache` 48 小时过期但没有 `maximumSize`。订单号等高基数锁 Key 会让本地锁对象长期累积。
- `RepeatExecuteLimit` 默认 duration=0 时只是并发互斥，不是时间窗口幂等；注解名和注释不能过度承诺。
- 四种 Redisson locker 捕获 `InterruptedException` 后直接返回 false，未恢复中断标志。unlock 也未检查当前线程持锁，finally 中的解锁异常可能掩盖原业务异常。
- `ProgramService` 1066 行、`OrderService` 895 行、`ProgramOrderService` 878 行，职责过多；三者还通过 self injection 绕过事务代理问题。
- 4 个服务启用 `allow-circular-references=true`，59 处字段注入集中于三大 Service，可维护性较弱。
- `pom.xml` 重复声明 fastjson 版本（后者覆盖前者），compiler plugin 较旧且使用 source/target 而非 release。应统一 BOM 与依赖锁定。
- 29 处 `System.out/printStackTrace` 主要位于工具、验证码和演示代码；低优先级清理即可。

### SQL/部署

- 新库脚本已经合并大部分可靠性表，Docker 首次初始化可按顺序执行；升级脚本依然依赖人工判断列/索引是否存在。
- 未使用 Flyway/Liquibase，没有 schema history、checksum 和自动回滚策略；面试时只能说“提供版本 SQL”，不能说“数据库迁移工程化完成”。
- `order_create_event_v5_link.sql` 等脚本缺少明确 `USE`，手工执行容易落错库。
- MySQL、Redis、Kafka、Nacos、Elasticsearch 均为面试单节点拓扑，适合演示，不具备生产 HA。

### 历史版本与项目身份

- v1-v4.1、旧 Lua、旧延迟队列、旧限流仍在源码和路由中。它们适合讲架构演进，但会让面试官误以为都是当前生产路径。
- 建议在 README 明确：v5 是唯一参考主链，旧版本仅用于对照实验，默认不作为能力承诺。
- 源码仍有大量“大麦/阿星”注释、包名和目录名。简历可以统一使用“星演”，但不能声称整个仓库从零原创。最稳妥的个人贡献口径是“在既有微服务骨架上主导交易可靠性与高并发治理重构”。

## 7. 测试代码静态评价

仓库有 13 个测试类、26 个 `@Test`，覆盖令牌桶、舱壁、Kafka ACK、延迟队列、迁移事件、模拟支付、Intent 状态、库存幂等和对账等关键组件，这是明显进步。

但缺少能够防住本报告 P0 的测试：

- 同毫秒 64+ 次 `getOrderNumber(userId)` 唯一性与位段属性测试；
- 分片算法多值 `IN` 跨库跨表路由测试；
- pay/cancel 并发只允许一个 CAS 成功；
- enqueue 与 processing 并发、ACK 后三份数据全部清理；
- 已售座位执行预热必须拒绝或保持 SOLD；
- 非法 JSON 进入 DLT 后能保存原文且不产生 `.DLT.DLT`；
- 终态 PAID/CANCELLED 的对账覆盖；
- Redis Cluster `CLUSTER KEYSLOT`/CROSSSLOT 合约测试。

本轮遵照要求没有执行现有测试，所以这里评价的是测试代码覆盖面，不是测试通过证明。

## 8. 推荐修改顺序

### 第一批：先恢复业务正确性

1. 重做订单号位布局，同时给节点 ID 分配加租约和 Hash Tag。
2. 修复数据库/表复杂分片算法的多值路由和精确物理名匹配。
3. 将订单支付/取消改成统一 `NO_PAY -> target` CAS，并绑定唯一且一致的迁移事件。
4. 拆分预热与演示重置，已售事实永不被普通预热覆盖。
5. 重写延迟任务 Lua 状态机，增加 attempts、dead queue 和完整 ACK。
6. DLT 使用独立容器，先存原文再解析，按 topic/partition/offset 去重。

### 第二批：补完整闭环

1. 对账加入终态游标、节目游标、集合不变量和报告持久化。
2. 定义创建订单永久失败后的 Saga 补偿/人工隔离策略。
3. 收紧 PayBill 和 RefundIntent 状态机；明确只支持全额退款或真正实现部分退款。
4. 恢复单账号限购并以数据库事实/CAS 为准，不把 Redis 计数放在不可回滚事务里。
5. 修复自动选座游标分页和 release Lua 的参数完整性。
6. 修复支付限流路径并清洗受信请求头。

### 第三批：提升面试证据和维护性

1. 为上述 P0/P1 建立单元、组件和故障矩阵测试。
2. Flyway/Liquibase 管理可靠性表升级。
3. 拆分巨型 Service、改构造器注入、关闭循环依赖。
4. 明确 v5 唯一入口，旧版本移入 legacy/demo profile。
5. README、简历和指标统一使用“至少一次 + 业务幂等 + 最终一致性”，删除“零丢失/Exactly Once/生产全部完成”等过度承诺。

## 9. 面试时最诚实也最有力量的结论

可以说：

> 我没有用分布式锁假装解决跨存储事务，也没有宣称 Kafka 提供端到端 Exactly Once。我的主链是在锁座前持久化可恢复 Intent，Redis Lua 只负责单节目原子预占，MySQL 本地事务写 Outbox，Kafka 采用至少一次投递，消费者依靠订单号/eventId 和状态 CAS 幂等，支付/取消再通过可靠迁移事件收敛 Redis，最后由对账任务发现长期不一致。当前仓库是面试演示级工程原型，单节点依赖与少数并发边界仍在持续收口。

不能说：

- “已经实现真正零丢失”；
- “Kafka `acks=all` 所以单节点也高可用”；
- “分布式锁保证了支付和取消强一致”；
- “Lua 全部支持 Redis Cluster”；
- “对账覆盖全部终态”；
- “系统达到某 QPS/TPS”——在没有本轮并发测试报告前不要填写数字。
