# 工程变更清单

本文记录可靠性相关的代码、配置、数据库和运维变更。新变更应按日期追加，不能只写在提交说明或聊天记录中。

## 2026-08-27：修复 V5 自动配座竞争、Lua 雪花 ID 精度并建立隔离测试夹具

### 自动配座与有限重试

- 自动配座不再从 ZSET 固定头部只取一组座位。`ReferenceSeatInventoryService` 以 `requestId` 的稳定 Hash 选择起始 rank，循环读取有界候选窗口，并在 Java 内构造多组相邻座位；不同请求会自然分散到不同区域。
- `ReferenceOrderOrchestrator` 最多尝试 5 组互不重复的候选座位；只有 Lua 返回 `SEAT_UNAVAILABLE` 或 `SEAT_OWNED` 时才换下一组，库存未预热、价格/票档变化、限购、幂等冲突等错误立即终止，不用盲目重试掩盖系统故障。
- 同一次请求的所有内部尝试复用同一个 `intentId`、`orderNumber`、`eventId`、请求指纹和过期时间。失败候选不会写幂等 receipt；Lua 仍然只对 1～6 个目标座位执行 O(k) 校验、迁移与 `XADD`，没有重新引入复杂选座逻辑。
- 新增 `reference-order.auto-seat-max-attempts`，默认 5；候选读取上限保持 2000，但通常收集到 32 组可用候选后即停止继续扫描。

### 运行检查额外发现并修复的 Lua 精度问题

- 首次真实检查在 Redis 明确存在 10000 个可售座位、owner/reservation 均为空时，单请求仍连续返回 `SEAT_UNAVAILABLE`。结构化日志证明候选座位实际存在，根因是 Redis Lua `cjson` 用 IEEE-754 double 解码 JSON number，超过 `2^53` 的雪花座位 ID 被舍入，`ZSCORE` 查询了错误成员。
- 进入 Lua 的 `seatId`、`ticketCategoryId`、`ticketUserId` 及 Redis seat metadata 中的 64 位 ID 全部改用 JSON string 承载；金额仍使用整数分。Java/Fastjson 回读时恢复为 `Long`，避免锁座结果和订单事件出现被舍入的座位号。
- 新增超过 `2^53` 的座位/观演人 ID 回归测试，防止未来把字符串协议误改回 JSON number。

### 可观测性

- 新增低基数指标：`xingyan_auto_seat_request_total`、`xingyan_auto_seat_conflict_total{code}`、`xingyan_auto_seat_retry_total{code}`、`xingyan_auto_seat_retry_count_total{retries}`、`xingyan_auto_seat_final_failure_total{reason}`。
- `requestId`、用户、节目、票档、候选座位、Lua 返回码、重试序号和单次 Lua 耗时只写结构化日志，不作为 Micrometer tag；`retries` 的取值由最大尝试次数限制。

### 隔离数据与运行脚本

- 新增 `tests/benchmark/prepare-v5-test.sql`、`reset-v5-test.sql` 和 PowerShell 公共库，保留节目 `900000`、票档 `900001`，生成 100 行 × 100 列共 10000 个座位、1000 个分片用户及对应观演人。测试名称统一使用“星演”，未引入旧项目品牌数据。
- 新增 `Prepare-XingyanV5Benchmark.ps1`：按真实分片写入数据、仅清理保留 ID 范围的 Redis Key、生成 JMeter CSV 并调用真实预热接口；存在活跃 reservation 或未支付订单时拒绝覆盖。
- 新增 `Cleanup-XingyanV5Benchmark.ps1`：优先通过真实取消接口释放未支付订单，确认 MySQL/Redis 收敛后才恢复专用节目；默认保留已取消订单证据，可显式 `-PurgeOrderHistory` 删除该节目历史，遇到已支付订单时拒绝重置。
- 新增 `Invoke-XingyanV5FunctionalCheck.ps1`，固定 JDK 17，依次执行 1、3、10 并发功能检查并自动清理。该脚本只验证正确性，不输出或宣称最大 QPS。

### 已执行验证与边界

- Program 相关单元测试共 15 项通过，包含候选冲突后换组、候选顺序不重复、Lua receipt 写入顺序、低基数指标和雪花 ID 精度回归；Maven Reactor 打包成功。
- 真实 JMeter 小规模检查共 14 个创建请求，1/3/10 三档均为 100% 业务成功；14 个订单号、14 个座位均唯一。测试后 14 个订单通过真实取消链路进入状态 2，MySQL 可售座位恢复为 10000，Redis available=10000、owner=0、reservation=0，Stream 长度与 PEL pending 均为 0。
- 本次正常分散样本没有触发冲突，运行指标为 `request_total=14`、`retry_count{retries=0}=14`；冲突重试分支由单元测试覆盖。尚未执行正式高并发、容量拐点、Redis/Kafka 故障注入，因此不能把本轮 57～199ms 的 10 并发样本解释为系统 QPS 上限。

## 2026-08-27：增加 V5 JMeter 创建接口基线计划

- 新增 `tests/jmeter/v5-create.jmx`，支持通过参数切换直连 Program 或 Gateway；每次请求生成唯一 `requestId`，并把业务码非成功、缺失订单号的 HTTP 200 响应计入失败率。
- 新增经运行中 User 服务接口验证归属的三组用户/观演人 CSV，仅用于 1～少量请求的脚本冒烟；节目 32 每账号限购 6 张，因此当前数据最多支持 18 笔新成功订单，禁止把该短样本吞吐量作为 V5 容量结论。
- 新增 JMeter CLI 使用说明，明确创建接口指标只代表准入响应；V5 正式报告还需统计订单最终可查询的端到端耗时。
- 固定压测终端把 JDK 17 的 `bin` 放到 `Path` 首位；本机仅设置 `JAVA_HOME` 仍会被 Oracle `javapath` 中的 Java 25 抢先，导致 JMeter/Groovy 报 `Unsupported class file major version 69`。
- 真实 QPS 基线前仍需建立专用压测节目、充足用户/座位和有范围的重置能力，保证每轮从相同数据快照开始。
- JDK 17 下单请求冒烟成功，直连 Program 的一次准入响应为 402ms，且异步订单已落入 MySQL。3 线程自动配座预检复现并发候选冲突：9 次请求仅 4 次成功，另外 5 次返回 `40003 座位已售卖`；根因是多个请求在 Lua 前读到相同候选座位，当前结果不能作为 QPS 容量数据。
- 本轮 JMeter 成功创建的 11 笔订单均已通过正常取消接口释放，Redis 中对应新增账号占用已回退；未直接删除业务表记录。

## 2026-08-26：Redis Stream 创建订单中继实时化、独立 PEL 恢复与链路指标

### 链路指标

- 新增 `xingyan_order_stream_wait_seconds{shard}`、`xingyan_order_kafka_send_seconds{shard,result}`、`xingyan_order_create_seconds{result}` 和 `xingyan_order_end_to_end_seconds{result}`。
- 新增 `xingyan_order_stream_pending`、`xingyan_order_stream_dead`、`xingyan_order_relay_inflight` 三个 Gauge；订单号、eventId 仅写结构化日志，不作为 Micrometer tag，避免高基数。
- `streamAddTime` 直接解析 Redis Stream recordId 毫秒段，不修改锁座 Lua；`kafkaSendAckTime` 在 Kafka Future 完成回调的第一时间采集，不把 ACK Executor 排队耗时误算为 Kafka 发送耗时。
- Stream 入流时间、Kafka 发送开始时间和分片号通过低基数 Kafka Header 传入订单服务；`orderCreatedTime` 由订单服务在短事务代理返回后、回执缓存写入前采集，表示订单事务已经提交。

### 实时 Relay

- 删除单定时线程串行扫描 16 个 Stream 且同步等待 Kafka Future 的旧 `RedisOrderCreateStreamRelay`。
- 新增 `RedisOrderCreateRealtimeRelay`：16 个 shard Worker 分别执行 `XREADGROUP BLOCK 1000ms COUNT n`，只读新消息，不在新消息热循环前恢复 PEL。
- 新增 `RedisOrderCreatePublisher`：全局 Semaphore 限制 in-flight，Kafka 全异步发送；Future 完成后转交有界 ACK Executor。Kafka 成功后才 `XACK`，随后尽力 `XDEL`；Kafka 失败或 XACK 失败时保留 PEL，由下游幂等吸收可能的重复。
- 新增 `RedisOrderCreateGroupInitializer` 和 `RedisOrderRelayProperties`，统一处理 `XGROUP CREATE ... MKSTREAM`、BUSYGROUP/NOGROUP 以及批量、idle、并发、停机等待参数。
- 停机先停止 Stream Worker，并在有限时间内等待在途回调；未完成或 ACK Executor 拒绝的记录不确认，继续留在 PEL。

### 独立 PEL Recovery

- 新增 `RedisOrderCreatePendingRecovery`，每 10 秒独立扫描 PEL；仅认领 idle 至少 60 秒且不在本机 in-flight 集合的记录。
- 当前 Spring Data Redis 版本未暴露 `XAUTOCLAIM` 高级接口，因此采用语义等价且兼容的 `XPENDING + XCLAIM`；Kafka `delivery.timeout.ms=30000`，claim idle 设为 60000ms，避免认领仍在正常发送的消息。
- 恢复发送继续共用全局 in-flight 背压。达到 20 次投递上限时，同槽 Lua 原子执行写 dead stream、`XACK` 和 `XDEL`；dead 与 source 带相同 `{sale:shard}` Hash Tag，不产生 Redis Cluster CROSSSLOT。
- 将死信原子迁移/重放从旧 Relay 拆入 `RedisOrderCreateDeadLetterService`；管理接口继续支持保留原 payload/eventId/orderNumber 的显式重放。

### 验证

- 增加 Relay 异步成功后才 XACK、Kafka 失败保留 PEL、Stream ID 时间解析、订单端成功/失败耗时指标和 source/dead 同槽测试。
- Program/Order 及依赖模块已执行 Maven Reactor 回归；Program 15 项、Order 11 项均通过。自动对账逻辑不在本轮修改范围，高并发与 Redis/Kafka 真实故障注入留待后续。

## 2026-08-26：修复订单确认页首次提交无反馈与重复点击

- 实名观演人改为受控复选框组，限制选择人数不超过票数，并持续显示“应选/已选”数量；选择数量不满足时禁用提交按钮。
- 提交按钮接入可见的处理中状态并禁止重复点击，不再通过隐藏观演人信息伪装加载效果。
- 删除与轮询内部截止时间竞争的第二个十秒定时器，改为单一超时出口；增加轮询 token，阻止已取消的异步查询重新启动定时器。
- 合并 v1～v5 前端下单分支的请求与异常处理：同步版本直接跳转，异步版本统一轮询，失败恢复按钮并展示明确消息。
- 重排观演人卡片布局，移除固定 136px 行高、超大左右内边距、114px 分隔空白和基于 `vmin` 的巨型复选框。
- 按本轮范围不处理现有观演人数据，也不新增姓名或证件格式校验。

## 2026-08-26：修复新增购票人中文姓名乱码

- 复现确认浏览器提交“袁祥凯”后，数据库保存为 UTF-8/系统默认字符集错误转换产生的乱码；绕过网关直调用户服务仍可复现，根因位于用户服务请求解码。
- 用户服务显式启用并强制 Servlet UTF-8 请求/响应编码；网关 JSON 请求体改为按 UTF-8 字节显式解码和重建，避免依赖 Windows/JVM 默认字符集。
- 修正用户 `2459985949493518340`、身份证尾号 `3715` 的现存购票人记录为“袁祥凯”，并清理对应列表缓存。
- 通过前端代理重新新增中文测试购票人并读取验证，接口脱敏结果为 `*祥凯`；测试记录验证后已删除。

## 2026-08-26：修复支付页误报“订单详情响应无效”

- 根因是接口统一返回字符串状态码 `"0"`，支付页和支付结果页却用 `response.code !== 0` 与数字比较，导致所有成功响应都被误判为失败。
- 订单详情、支付提交和支付状态查询统一改为 `String(response.code) !== '0'`，兼容当前字符串协议，也兼容未来数字状态码。
- Vite 生产构建验证通过。

## 2026-08-26：修复未预热节目假排队与实名认证姓名显示

- 修复 V5 仅预热节目 `4` 才能下单的问题：下单策略先检查 Redis 已发布的 `ready` 快照；仅冷节目首次请求在节目级分布式锁内从 MySQL 装载一次完整快照，锁内二次检查避免并发重复装载，正常热路径仍只访问 Redis。
- 保留预热期间的原子关闸、活跃 reservation 拒绝和完整快照校验，不允许冷启动兜底覆盖正在锁定的座位。
- 修复前端把所有 V5 业务错误和网络错误统一显示成“正在排队”的误导行为；只有服务端已经受理订单、轮询 10 秒仍未生成时才显示排队提示，明确失败直接展示后端消息。
- 每次提交前清空旧轮询结果并停止旧定时器，避免重复提交误跳转到上一笔订单。
- 修复实名认证姓名框错误使用 `password` 类型导致中文显示成圆点的问题；姓名改为文本输入，身份证继续掩码，并补充非空、中文姓名和身份证格式校验及提交异常提示。
- 验证：Program Maven Reactor `BUILD SUCCESS`，Vite 生产构建通过；Redis 无节目 `8` 快照时首次 V5 下单成功生成订单 `701820590092189701`，Redis Stream/Kafka 异步落单与取消成功；第二笔订单 `701820912734830597` 成功且节目预热次数保持 `1`，证明热路径未再次读取 MySQL。两笔验证订单均已取消。

## 2026-08-26：基础真实链路冒烟测试

- 使用 JDK 17 启动七个 Java 服务和 Vite 前端，五个 Docker 基础设施容器、七个健康端点及前端代理均验证通过。
- 对旧 Docker 数据卷执行座位 reservation 字段迁移并删除创建阶段旧 Intent/Outbox 物理表。
- 真实验证登录、购票人、节目与票档查询、v5 预热、Redis Stream/Kafka 异步建单、请求幂等、模拟支付失败/成功、取消释放和手动对账。
- 支付订单 `701810771453124613` 最终 SOLD；取消订单 `701811847099162629` 最终 RELEASED；Stream Consumer Group `pending=0`、`lag=0`，dead 为空。
- 发现但未在测试轮修改：前端体验账号与初始化数据不一致、两个前端环境文件仍保留旧 Intent/Outbox 注释、JDK 25 会在首次 ShardingSphere 分片 SQL 时触发 Groovy 兼容错误。
- 完整证据与测试边界见 `output/20260826_BASIC_SMOKE_TEST.md`；本轮不包含并发和故障注入。

## 2026-08-26：v5 取消 MySQL Intent/Outbox，改为 Redis Stream 热路径

### 架构变化

- v5 主链改为 `USER/PROGRAM/GLOBAL 限流 -> 并发舱壁 -> 有界 O(k) Lua 锁座并 XADD -> Redis Stream 中继 -> Kafka -> MySQL reservationId CAS/建单`。
- 创建订单路径完全取消节目库 `OrderIntent` 和 `OrderCreateEvent Outbox`：删除实体、Mapper、状态机、恢复任务、Publisher/Replay 服务以及 ShardingSphere 表规则，并提供旧表删除迁移。
- `intentId` 继续作为 Redis reservation/数据库座位的归属 token，不再对应 MySQL Intent 表。

### Redis 与 Kafka

| 范围 | 修改 |
| --- | --- |
| `SeatReservationKeys` | 节目按 16 个 `{sale:shard}` 分布；节目库存键与分片 Stream 同槽，解决 Redis Cluster 多键 Lua 的 CROSSSLOT。 |
| `referenceReserveSeats.lua` | 只处理 1～6 个目标座位；原子完成元数据/价格校验、账号配额、owner/reservation、到期索引和 `XADD`；保存请求指纹与原始消息用于幂等重放。 |
| Redis 幂等 receipt | reservationId 对应一个带 TTL 的 Redis String 回执；自动选座重试在重新选座前返回原 orderNumber，修改同 requestId 的业务参数会被拒绝。它不是 MySQL Intent，也不增加数据库热路径。 |
| `RedisOrderCreateRealtimeRelay` / `RedisOrderCreatePendingRecovery` / `RedisOrderCreatePublisher` | Consumer Group 实时读取与 PEL 恢复分离；Kafka 确认后才 XACK；达到上限原子转 dead stream；支持按 recordId 保留原 eventId/orderNumber 重放。 |
| `ReferenceReservationExpiryTask` | 宽限期后仅在源 Stream 事件仍存在、订单不存在且 MySQL 尚无该 reservation 锁座时释放；事件已交给 Kafka/dead 或事实查询失败时 fail-closed。 |
| Kafka | 继续使用 `acks=all`、幂等生产；订单消费者手动提交、有限重试和 Kafka DLT。 |

### 数据库与状态竞争

- `d_seat_*` 增加 `reservation_id` 与 `seat_version`；创建订单使用 `NO_SOLD + reservation_id IS NULL` 条件更新，并严格校验影响行数。
- 支付/取消只允许相同 reservationId 从 `LOCK` 迁移；释放时清空 owner，售出时保留 owner，阻止延迟取消 A 释放后来订单 B 的 ABA 窗口。
- v5 事件携带 `reservationExpireTime`；过期事件在节目库锁座 CAS 前被拒绝，避免到期释放后旧消息重新落库锁座。
- 已提交过库存操作的重试先命中幂等记录再检查截止时间，保证节目库锁座成功、订单库失败后仍可补建订单。
- 订单消费者先调用节目库存，再通过 Spring 自代理开启短订单事务，避免把订单库事务和连接跨在 Feign 调用之外；重复订单号会校验用户、节目和 reservation 归属。
- 新增 `20260826_order_inventory_operation.sql`、`20260826_seat_reservation_owner.sql` 与 `20260826_drop_order_intent_outbox.sql`；Docker 不再依赖混合了 Outbox 的旧建表脚本。

### 流量治理

- 网关从验签后的业务体提取并覆盖可信 `X-Xingyan-Program-Id`，新增 PROGRAM 维度。
- v5 依次执行 USER、PROGRAM、GLOBAL 三层 Redis 令牌桶；被限流请求不会提前占用下游并发 permit。
- 并发舱壁从验签过滤器拆为独立过滤器，顺序固定为验签 `-2`、限流 `-1`、舱壁 `0`。
- 删除热路径订单计数 RPC；节目缓存把服务端账号限额传入 Lua，由配额检查和锁座一次原子提交。

### 对账、文档与验证

- 对账执行器改为检查 Redis Stream/dead、活跃 reservation/owner/available 不变量和订单批量事实，不再查询或推进 Intent/Outbox。
- 新增 `ADR-002-redis-stream-order-event.md`，更新 README、架构演进、已知边界和 SQL 清单；ADR-001 标记为已取代。
- 已执行 Order/Program/Gateway 及依赖模块的 Maven Reactor 测试，当前均 `BUILD SUCCESS`；覆盖 Stream 下游消费、手动 ACK 失败传播、库存幂等、自动选座请求重试、终态 CAS、限流与舱壁。高并发、真实 Redis/Kafka 故障注入和前端链路测试留到后续阶段。

## 2026-08-25：全仓静态复审、简历重写与来源化面试题库

### 本轮边界

- 按用户要求仅进行静态审阅，不启动 Docker/服务，不执行单元、接口、前端、并发或故障注入测试。
- 读取 972 个源码与运行配置文件（约 78,693 行），排除构建产物、依赖目录、日志、IDE 元数据和自动生成的 `.flattened-pom.xml`。
- 本轮只新增审计与面试文档，没有修改业务代码、SQL、配置或运行环境。

### 新增文档

| 文件 | 内容 |
| --- | --- |
| `docs/audit/20260825_FULL_CODE_AUDIT.md` | 全仓覆盖口径、v5 数据流、工程化评分、6 组 P0、P1/P2、18 个 Lua 逐组结论与分阶段修复顺序。 |
| `docs/RESUME_PROJECT_XINGYAN_2026.md` | 完全按“星演”业务重新组织的简历条目、30/90 秒介绍、三分钟讲法、项目难点故事和禁用夸大词。 |
| `docs/INTERVIEW_BANK_SOURCED_2026.md` | 65 道项目/中间件/支付/设计/代码审查题；以牛客真实面经证明出现频率，以 Kafka、Redis、MySQL、Spring、ShardingSphere、JDK 官方资料校验技术答案。 |

### 复审结论（覆盖并修正本文件前序乐观表述）

- v5 主链已经实际接入 Intent 前置持久化、同事务 Outbox、Kafka 手动 ACK/重试/DLT、支付取消迁移事件、延迟取消与对账调度，不能再评价为“只有组件没有业务接线”。
- 但“可靠闭环已完成/全部问题解决/生产级”的表述不成立。静态复审发现：订单号 sequence 与 worker/datacenter 位段重叠；复杂分片算法对 `IN` 只路由首值；支付/取消没有统一 `NO_PAY -> target` CAS；普通预热会把 SOLD 重置为 NO_SOLD；延迟队列可留下无 payload 毒任务；DLT 毒 JSON 无法先落原始审计且可能形成 `.DLT.DLT`。
- 其他重要边界包括终态 Intent 不进入对账、部分退款模型冲突、单账号限购被注释、自动选座 offset 分页跳项、支付限流路径未覆盖真实入口、worker/DC 与 captcha Lua 的 Cluster CROSSSLOT，以及单 broker 使 `acks=all` 不具备副本容灾。
- 前序“已执行验证”是当时链路样例记录，不构成上述并发反例已被验证或修复的证明。完成 P0 修复并新增对应异常/并发测试前，简历只能使用“至少一次 + 业务幂等 + 最终一致性的工程化原型”口径。

## 2026-08-25：模拟支付与 v5 可靠闭环补强

### 业务与前端

| 范围 | 修改 |
| --- | --- |
| `PayChannel`、订单/支付 DTO、`PayResultVo` | 新增 `mock` 渠道和 `SUCCESS/FAILURE` 受控结果；支付统一返回 `INITIATED/PAID/FAILED/PENDING`，前端不再解析 HTML 判断状态。 |
| `MockPayStrategyHandler`、`PayService` | 账单准备、事务外渠道调用、结果确认拆成短事务；模拟失败保留 `NO_PAY`，模拟成功 CAS 为 `PAY`，重复成功幂等。 |
| `OrderService`、`payMethod.vue`、`paySuccess.vue` | 立即成功时推进订单闭环；同步失败返回 `PENDING` 并由 pay/check 收敛；页面可选择支付宝/模拟支付并分别触发成功或失败。 |
| `PayStrategyHandler`、支付宝/模拟退款 | 删除无稳定退款号的三参数接口，所有渠道编译期强制实现 `refundNo`；支付宝固定传 `out_request_no`。 |

### 可靠事件、恢复与库存

| 范围 | 修改 |
| --- | --- |
| `referenceBeginPreheat.lua`、`referenceFinishPreheat.lua`、`ReferenceSeatInventoryService` | 预热前原子关闸并拒绝活跃 owner/reservation；完整构建后原子发布 ready/version；异常保持 fail-closed 且允许下一次在节目锁下接管。 |
| `OrderCreateEventReplayService`、对账入口 | `DEAD/FAILED -> PENDING` 使用 CAS 并沿用原 eventId/orderNumber；受控允许对“已发送但无订单事实”事件重投。 |
| `OrderCreateDltRecord/Service/Consumer` | DLT 消息先持久审计再手动 ACK，保留原 payload、分区、offset 和异常，并提供显式重放。 |
| `ReservationTransitionEventService` | v5 支付/取消在订单本地事务写可靠迁移事件；提交后立即尝试，失败退避重试；PROCESSING 使用 lease 超时回收，同 commandId 幂等重投。 |
| Intent、延迟取消和对账任务 | Intent 增加独立 `next_retry_time`；清理无锁座事实的陈旧 INIT；Intent、订单兜底扫描和三层对账采用游标/退避，避免固定第一页饿死。 |
| 自动选座、网关限流、指标 | ZSET 候选改为最多 500/页并携带跨页尾窗口；配额拒绝 429、关键 Redis 依赖不可用 503；区分 Redis failure/local fallback 指标；对账指标统一为 `xingyan_*`。 |

### SQL、配置与运维

- 新增 `20260825_order_intent_retry_cursor.sql`、`20260825_reservation_transition_event.sql`、`20260825_order_create_dlt_audit.sql`，并接入星演 Docker 初始化和本地/生产分片规则。
- 当前 Docker 实例已执行增量迁移：8 张迁移事件表、8 张 DLT 审计表、4 张 Intent 表的 `next_retry_time` 均已核验存在；未创建 `damai*` 数据库或表。
- 新增 `reservation-transition.*`、Intent 退避、DEAD 自动重放配置和 Prometheus 告警；新增 `ops/Invoke-XingyanV5Load.ps1`、请求模板及 `docs/RELIABILITY_EXERCISES.md`。
- 运行基线固定为 JDK 17。JDK 25 会触发当前 ShardingSphere/Groovy 的 `Unsupported class file major version 69`，不能作为本项目演示运行时。

### 已执行验证

- 核心四模块 Maven reactor 全量测试通过；新增模拟支付 3 项、迁移事件 lease 回收 1 项，以及已有订单、网关、对账和 Lua 键槽测试均通过。
- `npm run build` 通过；前端 `/`、`/index`、`/order/payMethod` 均通过真实 HTTP 200 验证。
- 真实链路订单 `2092235681853096069`：模拟失败返回 `FAILED`，Order/PayBill 保持 `NO_PAY` 且 owner 保留；随后模拟成功返回 `PAID`，Order/PayBill/Intent 分别为 `PAY/PAY/PAID`，迁移事件 `SUCCEEDED`，owner/reservation 清空且 sold 包含座位；重复成功幂等返回已支付。
- 真实链路订单 `2092236841527836805`：活跃锁座时预热被拒绝，ready=60、maintenance 为空且 owner 未变化；随后取消成功，迁移事件 `SUCCEEDED`、Intent=`CANCELLED`、座位回到 available。订单 `2092238609137569925` 复测得到明确业务码 `50012`，清理后 owner 数为 0。

### 仍然存在的边界

- 当前预热是“维护态内原地重建 + ready/version 原子发布”，并非完整的版本化双缓冲 Key；它保证不清除活跃锁座和失败时不开放半成品，但预热期间不可下单。若面试官追问零停机，需要进一步让 Intent 绑定 inventoryVersion 并延迟清理旧版本。
- 本轮已提供 Kafka、进程退出、Redis 重启和退款提交窗口演练脚本/矩阵，但尚未逐项执行破坏性故障注入；不能把“机制和单元测试通过”表述成“所有 chaos 场景已实测”。
- 尚无 100/500/1000 并发真实性能数据；Lua 与分布式锁的性能结论仍必须由 `docs/BENCHMARK.md` 的同环境实验产生。

## 2026-08-25：v5 延迟取消生产接线与三层对账执行器

### 修改文件

| 文件 | 修改 |
| --- | --- |
| `damai-order-service/.../OrderService.java` | v5 创建订单本地事务提交后，直接生产 `pending` 延迟取消任务；Redis 故障不回滚订单，由 DB 扫描兜底。 |
| `damai-order-service/.../DelayCancelLeaseWorker.java`、`DelayCancelLeaseKeys.java`、`application.yml` | 仅扫描 v5 `NO_PAY` 超时订单；已支付、取消或退款任务视为幂等成功并 ACK；三个 Lua Key 共用 Cluster Hash Tag。 |
| `damai-program-service/.../OrderIntent*.java`、`ReferenceIntentOutboxService.java` | Intent 持久化 `orderNumber`，在同一 Intent/Outbox 事务内绑定；Outbox 消息携带 `intentId`。 |
| `damai-program-service/.../OrderCreateEvent.java`、`OrderCreateEventPublisher.java` | Outbox 显式落 `intentId` 与 `orderVersion`，不用扫描 JSON 识别 v5 事件。 |
| `ReferenceReconciliationExecutor/Task/Controller` | 新增事件、订单/支付、Redis 座位三层对账报告、受控手动入口及默认关闭的定时执行器。 |
| `damai-*-client/.../Reference*State*.java`、订单/支付 Controller | 新增受限 500 条的内部批量事实查询，避免对账读取完整订单和实名信息。 |
| `sql/reliability/20260825_order_intent_order_number.sql`、`20260825_order_create_event_v5_link.sql` | 为已部署表补充 v5 关联列和唯一索引；建表脚本同步更新。 |

### 解决的问题与取舍

- 延迟取消只在订单提交后入队，避免事务回滚却留下取消任务；`pending → processing → ACK` 与 lease 回收提供至少一次处理，数据库 `NO_PAY` 超时扫描承担可靠兜底。
- 对账以 Intent/Outbox 关联字段为入口：发现 `RESERVED` 无 Outbox、DEAD 事件、已发送未创建订单、订单与支付状态不一致、Redis 孤儿座位及库存不变量破坏。
- 订单事实存在时，执行器仅沿 `RESERVED → ORDER_CREATING → ORDER_CREATED` 的 CAS 合法边推进 Intent；不会强制覆盖订单、支付、退款或库存状态。
- 库存层在对账路径读取 `seat:meta` 推导票档并校验 `available + locked + sold = total`；这允许后台扫描，不进入抢票热路径。终态 Intent 仍占座仅报告，由恢复流程确认后补偿。

### SQL、配置、测试与性能

- 新增 `delay-cancel-lease.*`（默认 `enabled=false`）及 `reference-reconciliation.*`（默认 `enabled=false`）。
- 执行新增 ALTER 前需按每个节目分库、每张物理表检查列/索引是否已存在；本地尚未启动 MySQL、Redis、Kafka，未执行真实中间件迁移或故障演练。
- `mvn -T 4 -pl damai-server/damai-program-service,damai-server/damai-order-service,damai-server/damai-pay-service -am -DskipTests compile -q`：通过。
- `ReferenceReconciliationExecutorTest`、`ReferenceIntentOutboxServiceTest`、`OrderIntentStatusTest`、`ReferenceSeatReservationServiceTest`：共 6 项通过。
- 无真实性能结果；真实 Redis/Cluster 对账和锁座基准仍须按 `docs/BENCHMARK.md` 执行。

### 仍未解决的边界

- v5 HTTP 下单路由仍未开放，必须先完成 Intent → Lua → Outbox 的完整编排和真实 Redis/Kafka 故障演练；当前生产接线用于 v5 Kafka 消费创建出的订单。
- 数据库过期条件暂以 `create_order_time + timeout-ms` 推导，后续应将订单自身 `expire_time` 落库并作为唯一事实。
- 超时取消后 Intent 的座位释放、支付成功后明确销售态迁移仍需由 v5 编排/支付回调接入；对账目前报告异常而不猜测性修复。

## 2026-08-25：网关本机并发隔离语义修正

### 修改文件

| 文件 | 修改 |
| --- | --- |
| `damai-gateway-service/.../ConcurrencyBulkhead.java` | 新增非阻塞的 Semaphore 本机并发隔离器，以及当前并发、拒绝数、执行时间指标。 |
| `damai-gateway-service/.../RequestValidationFilter.java` | 仅成功获取许可才在 Reactor `doFinally` 中释放；满载返回 HTTP 503。 |
| `damai-gateway-service/.../BulkheadProperties.java`、`application.yml` | 新配置为 `gateway.bulkhead.*`，默认关闭。 |
| `damai-gateway-service/.../ConcurrencyBulkheadTest.java` | 覆盖拒绝、释放和重复释放。 |

### 解决的问题与取舍

- 原 `RateLimiter` 是并发阈值而非 QPS 令牌桶，并且在异步链路结束前错误释放许可；现已删除该误导性实现。
- 本机 bulkhead 满载返回 503，明确表示下游容量保护；分布式 QPS 限流将单独使用 Redis 令牌桶并返回 429/`Retry-After`。

### SQL、配置、测试与性能

- 本批无 SQL 和性能结果；新增网关单元测试。
- 分布式令牌桶、用户/节目维度规则和 Redis 故障降级尚未实现。

## 2026-08-25：Redis 分布式令牌桶与可观测拒绝响应

### 修改文件

| 文件 | 修改 |
| --- | --- |
| `damai-gateway-service/.../RedisTokenBucketRateLimiter.java`、`lua/tokenBucket.lua` | 新增 Redis Hash 令牌桶，补充、判断、扣减在一个 Lua 原子调用中完成。 |
| `damai-gateway-service/.../DistributedRateLimitFilter.java` | 在认证后按 GLOBAL / CHANNEL / USER / IP 规则限流；拒绝返回 429 和 `Retry-After`。 |
| `damai-gateway-service/.../RateLimit*.java` | 新增规则、维度、故障策略及完整决策结果模型。 |
| `damai-gateway-service/application.yml` | 增加查询 IP、下单用户、支付用户示例规则，默认关闭。 |

### 解决的问题与取舍

- Redis Key 使用 `damai:rate:{route}:dimension:hashed-value`，route Hash Tag 使单桶 Lua 满足 Cluster 键槽约束；维度值哈希避免在 Redis 键中暴露用户或 IP。
- 查询规则 Redis 故障时采用进程内近似令牌桶；订单与支付采用 fail-closed。全局保护仍由本机 bulkhead 承担，避免制造超热的“绝对全局 QPS”单 Key。

### SQL、配置、测试与性能

- 本批无 SQL 和性能结果；新增 Lua 决策解析单元测试。
- 尚未用真实 Redis/Cluster 执行脚本集成测试；Redis 调用仍经当前项目的同步 RedisTemplate，后续压测需评估网关事件循环影响。

## 2026-08-25：v5 有界 Lua 锁座基础设施

### 修改文件

| 文件 | 修改 |
| --- | --- |
| `damai-program-service/.../reference/SeatReservationKeys.java` | 新增 v5 座位 Meta、ZSET 可售集合、Owner、Reservation、Result 键模型。 |
| `damai-program-service/.../ReferenceSeatReservationService.java`、`referenceReserveSeats.lua` | 新增 O(k) 手动候选原子确认、Intent 重放结果与服务器座位快照。 |
| `docs/adr/001-lua-vs-distributed-lock.md` | 记录 Java 候选计算 + Lua CAS 的取舍。 |

### 解决的问题与取舍

- 旧 v31 脚本在 Redis 主线程中执行 `HVALS`、JSON 解析和排序；v5 脚本禁止全场扫描，仅处理本请求 seatId。
- v5 键统一使用 `{program:<id>}` Hash Tag，所有真实键均通过 KEYS 传入。该基础设施尚未接管旧座位缓存或订单接口，以免两个库存模型同时售卖。

### SQL、配置、测试与性能

- 本批无 SQL 或性能结果；新增键槽一致性及重复 seatId 前置校验测试。
- 后续需要实现 v5 缓存初始化、ZSET 有界候选、Intent 持久化与超时释放，才能暴露 v5 下单接口。

## 2026-08-25：v5 订单 Intent 状态机基础设施

### 修改文件

| 文件 | 修改 |
| --- | --- |
| `damai-program-service/.../OrderIntent.java`、`OrderIntentMapper.java` | 新增可分片的订单 Intent 持久化模型。 |
| `damai-program-service/.../OrderIntentService.java` | 新增 requestId 幂等创建及按版本号 CAS 的合法状态迁移。 |
| `sql/reliability/20260825_order_intent.sql` | 新增 2 库 2 表迁移。 |
| 两个 `shardingsphere-program-*.yaml` | 为 Intent 添加以 `program_id` 为分片键的稳定路由。 |

### 解决的问题与取舍

- Intent 先于 Redis 预占创建，进程硬终止后可由数据库扫描定位，而不是依赖内存异常回滚。
- 状态机拒绝 `CANCELLED -> ORDER_CREATED` 等非法覆盖；后续 v5 编排将在 `RESERVED` 与 Outbox 写入同一数据库事务。

### SQL、配置、测试与性能

- 新增 SQL 迁移，尚未在本地 MySQL 执行；新增状态迁移单元测试。
- v5 请求编排、Redis 初始化、Outbox 同事务写入与恢复调度尚未接入，v5 路由仍不开放。

## 2026-08-25：面试设计材料与故障边界

### 修改文件

| 文件 | 作用 |
| --- | --- |
| `docs/BUSINESS_INVARIANTS.md` | 定义座位、Intent、订单和支付的验收不变量。 |
| `docs/FAILURE_MATRIX.md` | 明确故障点、处理动作与已实现/待实现边界。 |
| `docs/BENCHMARK.md` | 固化四类锁座实现的压测矩阵与必报指标。 |
| `docs/INTERVIEW_QA.md`、`docs/adr/002~004` | 固化关键取舍和面试表达。 |

### 仍未解决的边界

- 文档不替代实现或性能数据；标为“待实现”的恢复、超时取消、支付状态机和基准模块必须继续交付后才能宣称最终 v5 完成。

## 2026-08-25：独立锁座基准模块骨架

### 修改文件

| 文件 | 作用 |
| --- | --- |
| `pom.xml`、`damai-benchmark/pom.xml` | 新增不参与线上部署的独立基准模块。 |
| `SeatReservationBenchmarkPlan.java` | 固化四种实现和初始场景参数，供压测执行器和报告复用。 |
| `damai-benchmark/README.md` | 规定结果文件和禁止预填性能结论。 |

### 仍未解决的边界

- 当前只提供实验定义，尚未接入 JMH/Gatling、真实 Redis 或 3 主 Cluster，因此没有性能结果。

## 2026-08-25：v5 ZSET 候选读取与 fail-closed 初始化

### 修改文件

| 文件 | 作用 |
| --- | --- |
| `ReferenceSeatInventoryService.java` | 显式写入不可变 Meta 与可售 ZSET，并从有限候选计算相邻座位。 |

### 解决的问题与取舍

- 自动选座不在 Lua 中执行全场扫描；ZSET 仅取固定候选窗口，Java 再计算连座。
- v5 Key 缺失时下单 fail-closed；初始化只允许开售前的受控全量快照，不能用滞后数据库读直接重新开售。

## 2026-08-25：Intent 与 Outbox 同事务编排

### 修改文件

| 文件 | 作用 |
| --- | --- |
| `ReferenceIntentOutboxService.java` | 在一个本地事务内将 `RESERVING -> RESERVED` 并持久化既有可靠事件 Outbox。 |
| `ReferenceIntentOutboxServiceTest.java` | 覆盖状态迁移失败时不继续写 Outbox 的编排顺序。 |

### 仍未解决的边界

- Kafka 投递仍由现有 Outbox Relay 至少一次执行；v5 路由、异常扫描恢复和库存补偿尚未接入。

## 2026-08-25：支付回调状态 CAS

### 修改文件

| 文件 | 修改 |
| --- | --- |
| `damai-pay-service/.../PayService.java` | 支付回调仅允许 `NO_PAY -> PAY` 的条件更新；取消/退款后的乱序回调返回失败，要求渠道重试或进入对账。 |

### 仍未解决的边界

- 退款 Intent、稳定退款幂等号、订单状态联动和支付/订单对账任务尚未实现。

## 2026-08-25：v5 Intent 过期补偿

### 修改文件

| 文件 | 修改 |
| --- | --- |
| `referenceReleaseSeats.lua`、`ReferenceSeatReservationService.java` | 新增按 Intent Owner 校验的幂等释放，恢复 ZSET 可售座位并清理预占记录。 |
| `OrderIntentRecoveryTask.java` | 默认关闭的数据库扫描兜底，补偿过期 `RESERVED` Intent。 |

### 仍未解决的边界

- 当前补偿任务依赖已保存的服务端座位快照；v5 路由和延迟队列 lease/ACK 接入前不能在生产开启。

## 2026-08-25：退款 Intent 持久化模型

### 修改文件

| 文件 | 修改 |
| --- | --- |
| `damai-pay-service/.../RefundIntent.java`、`RefundIntentMapper.java` | 新增退款事实模型，`refundNo` 作为稳定渠道幂等号。 |
| `sql/reliability/20260825_refund_intent.sql` | 新增支付分库下的退款 Intent 表。 |

### 仍未解决的边界

- 当前退款入口尚未改为先创建 Intent、再调用渠道并由重试任务恢复；必须在迁移执行后接入，不能提前声称已具备退款可靠性。

## 2026-08-25：退款 Intent 与渠道幂等号接入

### 修改文件

| 文件 | 修改 |
| --- | --- |
| `PayService.java` | 退款前创建/复用 Intent；成功后标记 `SUCCEEDED`，重复请求返回稳定退款号。 |
| `PayStrategyHandler.java`、`AlipayStrategyHandler.java` | 新增稳定 `refundNo` 参数；支付宝映射为 `out_request_no`。 |
| 两个支付分片 YAML | 为 `d_refund_intent` 增加以订单号分片的路由。 |

### 仍未解决的边界

- 当前渠道调用仍位于本地事务中，且 FAILED Intent 的后台重试任务尚未接入；退款账单写入仍需增加唯一约束以完全抵抗极端并发。

## 2026-08-25：FAILED 退款 Intent 重试

### 修改文件

| 文件 | 修改 |
| --- | --- |
| `RefundIntent.java`、退款迁移 | 保存渠道与原因，支持无请求上下文的重试。 |
| `PayService.java`、`RefundIntentRetryTask.java` | FAILED Intent 通过 CAS 认领后使用原 `refundNo` 重试；默认关闭。 |

## 2026-08-25：延迟取消 lease/ACK 队列 Lua 基础

### 修改文件

| 文件 | 修改 |
| --- | --- |
| `delayClaim.lua`、`delayAck.lua`、`delayRequeueExpired.lua` | 新增 pending/processing/ACK/lease 过期回收原子操作。 |
| `docs/adr/005-delay-cancel-lease-queue.md` | 固化 Redis 及时性 + DB 扫描可靠性兜底取舍。 |

### 仍未解决的边界

- v5 生产者、消费者与数据库到期扫描尚未接到这些脚本；旧 Redisson 延迟队列不具备该 lease 语义。

## 2026-08-25：v5 延迟取消 lease/ACK Worker

### 修改文件

| 文件 | 修改 |
| --- | --- |
| `DelayCancelLeaseQueue.java` | 封装 Redis 原子入队、领取、ACK 和 lease 回收。 |
| `DelayCancelLeaseWorker.java` | 默认关闭的消费者：取消成功才 ACK，异常等待 lease 重领。 |
| `delayEnqueue.lua` | 补充 payload 与 pending 的原子入队。 |

### 可靠性边界

- Worker 增加 `NO_PAY` 且创建时间超过 15 分钟的数据库兜底扫描；过期时长需在 v5 订单配置接入后替换为订单自身 `expire_time`。

## 2026-08-25：订单策略版本唯一化与架构演进基线

### 修改文件

| 文件 | 修改 |
| --- | --- |
| `damai-common/.../ProgramOrderVersion.java` | 修复 v21、v31、v41 重复注册 Key；新增带 `EXPERIMENTAL` / `REFERENCE` 阶段的 v5 枚举。 |
| `damai-program-service/.../ProgramOrderContext.java` | 使用实例级注册表；重复策略 Key 在启动时失败，禁止覆盖。 |
| `damai-program-service/.../ProgramOrderController.java` | Swagger 将 v1～v41 标注为实验接口。 |
| `damai-program-service/.../ProgramOrderContextTest.java` | 新增版本 Key 唯一性和重复策略启动失败测试。 |
| `docs/ARCHITECTURE_EVOLUTION.md` | 新增版本对比、注册约束和最终参考链路。 |
| `docs/KNOWN_BOUNDARIES.md` | 明确安全范围和可靠性声明边界。 |

### 解决的问题与取舍

- 修复历史版本通过相同 Key 覆盖注册、实际执行策略取决于 Bean 顺序的问题。
- v1～v41 保留为可运行的对比实验，避免删除已有学习材料；v5 预留为唯一最终参考实现，尚未暴露空接口。

### SQL、配置、测试与性能

- 本批无 SQL 或配置变化，也未产生性能结果。
- 新增 2 个单元测试：枚举 Key 唯一性、重复策略启动失败。

### 仍未解决的边界

- v5 策略、Intent、限流和有界 Lua 尚未实现，将在后续批次分别交付。

## 2026-08-25：创建订单 Kafka 与库存对账可靠闭环

### 变更目标

- 创建订单消息从直接发送 Kafka 改为可恢复的本地可靠事件；
- 生产端明确强确认，消费端成功后才提交 offset；
- 消费失败有限重试并进入 DLT；
- Kafka 重投或远程响应丢失时不重复扣减节目库存；
- 启用库存对账调度，修复误处理和无法收尾问题；
- 为事件积压、死信、消费重试和库存对账增加指标及告警。

### 新增文件

| 文件 | 作用 |
| --- | --- |
| `damai-server/damai-program-service/src/main/java/com/damai/entity/OrderCreateEvent.java` | 创建订单可靠事件实体 |
| `damai-server/damai-program-service/src/main/java/com/damai/enums/OrderCreateEventStatus.java` | 事件待发送、发送中、失败、成功、DEAD 状态 |
| `damai-server/damai-program-service/src/main/java/com/damai/mapper/OrderCreateEventMapper.java` | 可靠事件数据访问 |
| `damai-server/damai-program-service/src/main/java/com/damai/service/kafka/OrderCreateEventPublisher.java` | 事件持久化、即时投递、后台中继、超时恢复和指标 |
| `damai-server/damai-program-service/src/main/java/com/damai/service/kafka/CreateOrderTopicConfiguration.java` | 创建主 Topic/DLT，并校验副本数 |
| `damai-server/damai-program-service/src/main/java/com/damai/entity/OrderInventoryOperation.java` | 节目库存操作幂等记录 |
| `damai-server/damai-program-service/src/main/java/com/damai/mapper/OrderInventoryOperationMapper.java` | 库存幂等记录数据访问 |
| `damai-server/damai-order-service/src/main/java/com/damai/config/KafkaConsumerReliabilityConfiguration.java` | 手动提交、有限重试和 DLT 恢复器 |
| `damai-server/damai-order-service/src/main/java/com/damai/config/CreateOrderTopicConfiguration.java` | 订单服务侧 Topic/DLT 声明及副本校验 |
| `sql/reliability/20260825_create_order_reliable_event.sql` | 两库四表的可靠事件和库存幂等表迁移 |
| `sql/reliability/README.md` | 上线顺序、生产参数与故障演练清单 |
| `ops/prometheus/damai-reliability-alerts.yml` | Prometheus 可靠性告警规则 |
| `damai-server/damai-order-service/src/test/java/com/damai/service/kafka/CreateOrderConsumerTest.java` | 消费提交与异常行为测试 |
| `damai-server/damai-order-service/src/test/java/com/damai/service/OrderTaskServiceTest.java` | Redis 流水隔离和对账收尾测试 |
| `damai-server/damai-program-service/src/test/java/com/damai/service/ProgramServiceInventoryIdempotencyTest.java` | 远程库存操作幂等测试 |

### 修改文件与行为

| 文件 | 主要修改 |
| --- | --- |
| `damai-server-client/damai-order-client/src/main/java/com/damai/domain/OrderCreateMq.java` | 增加 `eventId`，作为端到端事件标识 |
| `damai-server-client/damai-program-client/src/main/java/com/damai/dto/ReduceRemainNumberDto.java` | 增加 `orderNumber`、`eventId` 库存幂等键 |
| `damai-server/damai-program-service/src/main/java/com/damai/service/ProgramOrderService.java` | 去除无界异步等待；先持久化可靠事件，再尝试发送；落库失败回滚 Redis 预占 |
| `damai-server/damai-program-service/src/main/java/com/damai/service/kafka/CreateOrderSend.java` | 返回 Kafka 发送 Future，由可靠事件发布器等待 broker 确认 |
| `damai-server/damai-program-service/src/main/java/com/damai/service/kafka/KafkaTopic.java` | 统一生成带环境前缀的完整 Topic 名称 |
| `damai-server/damai-program-service/src/main/java/com/damai/service/ProgramService.java` | 在锁座和扣票前检查订单库存幂等记录，并与库存更新在同一本地事务提交 |
| `damai-server/damai-order-service/src/main/java/com/damai/service/kafka/CreateOrderConsumer.java` | 业务成功后手动确认；异常重新抛出；延迟消息只告警、不丢弃 |
| `damai-server/damai-order-service/src/main/java/com/damai/service/OrderService.java` | 远程库存副作用前检查订单幂等；失败交给 Kafka 重试/DLT，不重复写废弃订单列表 |
| `damai-server/damai-order-service/src/main/java/com/damai/scheduletask/ReconciliationTask.java` | 启用定时任务、增加分布式锁、同步执行、修正响应判断并增加指标 |
| `damai-server/damai-order-service/src/main/java/com/damai/service/OrderTaskService.java` | 只迁移当前数据库订单对应的 Redis 流水；支持上次成功后的幂等收尾 |
| `damai-server/damai-order-service/src/main/java/com/damai/service/handler/ProgramRecordHandler.java` | 去除递归睡眠重试，让失败回滚并交给下一轮调度 |
| `damai-server/damai-order-service/src/main/java/com/damai/service/delayconsumer/DelayOrderCancelConsumer.java` | 订单尚未创建时标记消费失败，允许异常消息对账重投，不能误报取消成功 |
| 两个服务的 `application.yml` | 生产端 `acks=all`、幂等生产、手动提交、重试次数、对账周期和 Topic 参数 |
| 节目服务两个 ShardingSphere YAML | 增加可靠事件表和库存幂等表的分库分表规则 |
| 两个服务的 `pom.xml` | 增加测试依赖；清理订单服务重复的 `spring-kafka` 声明 |

### 当前可靠性语义

- 可靠事件及 Kafka 消费均为 **至少一次**，通过 `eventId`、订单号和库存操作记录实现幂等；
- Kafka 已确认但事件状态更新失败时允许重复投递；
- 订单创建成功后才提交 offset；消费总尝试次数为 3，最终写入 `<topic>.DLT`；
- 事件发送 20 次仍失败时进入数据库 `DEAD` 状态并触发告警；
- Topic 由服务启动时声明，生产配置建议副本数 3、`min.insync.replicas=2`；
- 库存对账默认每 3 分钟执行一次，并用分布式锁避免多实例重复调度。

### 验证结果

- `mvn -pl damai-server/damai-order-service,damai-server/damai-program-service -am -DskipTests compile`：通过；
- 创建订单消费测试 3 个：通过；
- 库存对账隔离与收尾测试 2 个：通过；
- 节目库存幂等测试 1 个：通过。

本地 MySQL、Redis、Kafka、Nacos 未启动，因此本次没有执行真实中间件端到端故障注入。上线前必须按照 `sql/reliability/README.md` 执行迁移和演练。

### 已知边界与后续决策

Redis Lua 锁座与 MySQL 可靠事件落库不属于同一个事务。普通异常会回滚 Redis，但在 Lua 成功后、事件落库前发生进程硬终止，仍可能留下没有 MySQL 事件的 Redis 预占。

当前项目建议采用“数据库订单意图 + 幂等 Lua + Outbox 状态机”消除无追踪窗口，设计决策见 `docs/ADR-001-create-order-intent-outbox.md`。

---

## 2026-08-25：星演 v5 订单闭环、运行时修复与真实前端验收

本轮不以“形式上补几个配置”为目标，而是将创建订单参考链路实际运行到 Docker 中间件、后端服务和前端代理，验证订单意图、Redis 锁座、Outbox、Kafka、订单落库和查询闭环。并发压测与故障注入按阶段拆分，未纳入本轮通过结论。

### 1. 创建订单 v5 参考链路

| 文件或目录 | 主要修改 |
| --- | --- |
| `damai-server-client/damai-program-client/.../ProgramOrderCreateDto.java` | 客户端传入并复用稳定 `requestId`，为重复提交提供业务幂等键 |
| `damai-server/damai-program-service/.../entity/OrderIntent.java`、`.../enums/OrderIntentStatus.java` | 扩展订单意图的请求载荷、订单号、金额、版本和 `PAID` 等状态，支持完整恢复 |
| `damai-server/damai-program-service/.../service/OrderIntentService.java`、`ReferenceOrderOrchestrator.java` | 在锁座前持久化订单意图；按状态推进锁座、事件落库和订单创建，重复请求返回同一结果 |
| `.../strategy/ProgramOrderV5Strategy.java`、`.../controller/ProgramOrderController.java` | 增加 `/create/v5` 参考入口，保留旧入口用于对比，前端正式改走 v5 |
| `.../service/ReferenceIntentOutboxService.java`、`.../scheduletask/OrderIntentRecoveryTask.java` | Outbox 与意图在本地事务落库；扫描中间态意图并恢复发布，消除“Redis 预占无任何持久化追踪”的窗口 |
| `.../service/ReferenceSeatReservationService.java`、`ReferenceReservationTransitionService.java` | 封装锁座、释放和售出确认；支付/取消只允许合法状态转换并保持幂等 |
| `damai-server/damai-program-service/src/main/resources/lua/reference*.lua` | 预占、释放、确认售出全部使用 Redis Lua 原子执行；库存、元数据、归属、结果与最终状态统一 `{program:id}` hash tag，规避 Cluster CROSSSLOT |
| `damai-server-client/damai-order-client/.../OrderCreateMq.java` | 消息携带 `eventId`、`intentId`、请求标识和订单信息，形成端到端追踪链 |
| `damai-server/damai-order-service/.../kafka/CreateOrderConsumer.java`、`.../service/OrderService.java` | 消费成功后手动 ACK；失败交由重试/DLT；订单、意图和事件幂等，重复消息不重复建单 |
| `damai-server/damai-pay-service/...`、订单/节目远程接口及 DTO | 支付成功确认 Redis 座位为已售，取消或超时释放预占；使用订单版本和状态条件更新防止乱序覆盖 |

### 2. Kafka、延迟取消、退款与对账工程化

| 文件或目录 | 主要修改 |
| --- | --- |
| 节目与订单服务 Kafka 配置、`CreateOrderSend.java`、Topic 配置 | 生产端启用 `acks=all` 和幂等生产；发送必须等待 broker 确认；消费端手动提交、有限重试并写入 DLT |
| `DelayCancelLeaseQueue.java`、`delayClaim.lua` 及延迟消费/调度代码 | 延迟取消改为 pending/processing 租约队列，成功显式 ACK，宕机任务可 reclaim，并保留数据库扫描兜底 |
| `ReconciliationTask.java`、`OrderTaskService.java`、`ProgramRecordHandler.java` | 真正启用并监控库存对账调度；分布式锁键改为合法 SpEL 字面量；失败不递归睡眠，由后续批次重试 |
| 退款服务、退款意图与重试任务 | 第三方退款调用移出数据库事务；稳定 `refundNo` 保证渠道幂等，失败意图由调度补偿 |
| 分表路由与公共 `ServiceLock` 相关代码 | 补充分片键缺失时的可控广播/兜底查询，修正锁表达式解析和释放语义 |

### 3. 限流与业务可用性

| 文件或目录 | 主要修改 |
| --- | --- |
| `damai-gateway/...` 限流过滤器、配置与测试 | Redis 调用移出 Netty 事件线程；Redis 异常时使用有界本地兜底；明确区分 429 限流与 503 限流组件不可用；补充 v5 路由 |
| 前端提交逻辑 | 每次用户提交生成一次 `requestId`，网络重试复用该值，避免按钮连点或代理重试生成多张订单 |

### 4. 星演品牌、Docker 与数据库重建

| 文件或目录 | 主要修改 |
| --- | --- |
| `ops/docker-compose.interview.yml` | Compose 项目、容器身份和持久卷改为 `xingyan-interview` / `xingyan_*`，重建 MySQL、Redis、Kafka、Nacos、Elasticsearch |
| `sql/cloud/**`、`sql/reliability/**` | 数据库名由 `damai_*` 改为 `xingyan_*`；示例品牌、测试账号密码和 HTML 扩展属性改为星演语义 |
| 各服务 `application.yml` 与 ShardingSphere YAML | JDBC 库名、Redis/Kafka 前缀、Nacos服务名及运行参数改为 `xingyan`；数据库可靠事件/意图/库存幂等表路由可实际执行 |
| 公共常量 `Constant.DEFAULT_PREFIX_DISTINCTION_NAME` | 默认服务前缀由 `damai` 改为 `xingyan`；执行全量 clean package，避免 `static final` 常量被旧 class 内联 |
| `vue3/package.json`、`vue3/index.html`、`vue3/src/**` | 页面标题、展示文案、API 网关前缀与请求地址改为星演；前端开发代理可直连本轮后端链路 |
| 指标/告警与运行配置 | Redis key、Kafka Topic、服务发现、指标和告警标签统一为 `xingyan` 前缀 |

本轮只移除了这次生成的 5 个旧 `damai-interview-*` 容器、对应网络与 4 个卷，未触碰工作区外的 ZooKeeper 等无关资源。运行库现为 10 个 `xingyan_*` schema，未残留 `damai_*` schema；旧测试 Topic 与旧前缀 Redis key 已清理。

### 5. 运行时发现并修复的问题

1. Fastjson 2.0.9 兼容层会把嵌套 Java record 序列化成 `[{}]`，导致 Lua 收不到座位字段。现改为显式 `List<Map<String, Long>>`，并兼容解析旧失败结果。
2. Lua 返回空 table 时会编码为 `{}`，Java 端误按数组反序列化。`delayClaim.lua` 和锁座失败结果显式返回 JSON 空数组，Java 端同时兼容空值、`{}` 和 `[]`。
3. `@ServiceLock(keys = {"all"})` 会把 `all` 当作 SpEL 变量。现改为字符串字面量 `"'all'"`，运行日志已确认调度进入库存对账主体。
4. 修改公共 `static final` 服务前缀后，仅增量打包不会重编译所有调用方。现已 clean 构建依赖链，Feign 实际目标均为 `xingyan-*`。
5. v5 Redis 初始化采用 fail-closed：座位元数据或库存未完整加载时拒绝锁座，避免把缓存缺失误判为售罄或生成不可恢复订单。

### 6. 新增或加强的测试

| 测试 | 覆盖内容 |
| --- | --- |
| `ReferenceSeatReservationServiceTest` | Lua 字段契约、显式座位 JSON、失败返回和旧 `{}` 兼容 |
| `DelayCancelLeaseQueueTest` | 空批次解码、processing 租约领取 |
| `CreateOrderConsumerTest` | 手动 ACK、异常重抛、重复消息幂等 |
| `OrderTaskServiceTest`、库存幂等/对账测试 | 数据库隔离、Redis 流水收尾、重复扣减保护 |
| 网关限流测试 | Redis 正常、限流及本地兜底语义 |

### 7. 验证结果

- Maven 全模块测试：47 个模块构建通过；网关、订单、节目关键测试全部通过。
- 前端 `npm run build`：通过（仅保留依赖的 `::v-deep` 和 chunk size 警告）。
- Docker：MySQL、Redis、Kafka、Nacos、Elasticsearch 五个星演容器均为 healthy。
- 服务：网关、用户、节目、订单、支付、基础数据服务完成注册与互调；库存对账任务已实际启动。
- 前端真实代理 E2E：经 `http://127.0.0.1:5173/xingyan-dev/.../create/v5` 创建订单成功，重建后的最终验收订单号为 `2092211361055506565`；前端缓存查询和数据库详情查询均成功，节目标题与场馆中文正常。
- 同一 `requestId` 重放返回同一订单；数据库中对应订单意图 1 条、Outbox 事件 1 条，事件已发送；Kafka 实际 Topic 为 `xingyan-create_order` 及其 DLT。
- Redis 校验：座位 owner、reservation、result 均可追踪，总座位守恒；失败调试请求的预占已使用正式释放 Lua 回滚。

### 8. 清理记录与明确边界

- 删除了首轮失败 E2E 产生的精确测试意图、事件和旧 `damai-create_order` 测试 Topic，并通过释放 Lua 归还对应座位。这些仅为本轮生成的调试数据，删除不可恢复，但不涉及原始业务数据。
- 初始化 SQL 已由 `damai_*.sql` 全部改名为 `xingyan_*.sql`，历史扩容脚本和 Prometheus 告警也已改为星演库名、文件名、规则组及告警名。为避免一次高风险大重构，本轮仍没有重命名 Java 根包 `com.damai` 和 Maven 模块目录 `damai-*`；它们仅作为源码兼容标识保留，不会进入运行库、容器、Topic、Redis key、服务名及前端品牌。若面试展示要求源码树也完全无旧标识，应单独进行全包名/模块名迁移并重新跑全量回归。
- Docker 入口导入曾因恢复旧 `character_set_client` 把 UTF-8 中文写成双重编码。所有星演种子 SQL 现已在 `USE` 后显式执行 `SET NAMES utf8mb4`；删除并重建本项目 MySQL、Redis、Kafka 三个生成数据卷后，数据库原始字节和前端响应均验证为正确中文。
- Redis 数据卷重建后，存活进程保留旧 Redisson BloomFilter 参数会拒绝访问新实例；节目服务已按正确流程重启并重新预热。v5 库存未预热时继续 fail-closed，不会为了可用性偷偷按陈旧数据库快照补库存。
- 本轮结论是“单用户真实完整链路可执行且幂等重放通过”，不等同于已经证明高并发与故障恢复。并发抢座、Kafka/Redis/MySQL 中断、进程 `kill -9` 和恢复收敛将在下一阶段单独测试并记录证据。

---

## 2026-08-27：修复 Redis Stream Relay 全分片失效

### 故障表现与根因

- v5 Lua 能成功锁座并原子 `XADD`，前端也拿到订单号，但订单缓存持续为空，最终显示排队失败；
- Redis Stream 存在未消费记录且消费组 `lag > 0`，Kafka offset 没有增长，订单库没有落单；
- `RedisOrderCreateGroupInitializer` 使用 `RedisConnection.execute("XGROUP", ...)` 初始化消费组；项目运行时连接实现是 Redisson，`RedissonConnection.execute` 会直接抛出 `UnsupportedOperationException`；
- 16 个实时 Worker 因此全部进入每秒重试，任何新 Stream 事件都无法进入 Kafka。这是本次“一个订单都下不了”的直接原因。
- 修复消费组初始化后又发现第二个阻塞点：每个分片 Worker 在读消息前按 `batch-size=100` 抢占全局 permit，而全局上限只有 200；最先启动的两个空闲分片各占 100 后阻塞读流，其余 14 个分片永远拿不到 permit。因此会出现少数节目偶尔成功、绝大多数节目一直排队的假象。

### 修改清单

| 文件 | 修改 |
| --- | --- |
| `RedisOrderCreateGroupInitializer.java` | 改用 Spring Data `StreamOperations.createGroup(..., ReadOffset.from("0-0"), ...)`，通过 Redisson 已支持的 `xGroupCreate` 创建消费组并启用 `MKSTREAM`；保留 `BUSYGROUP` 幂等处理 |
| `RedisOrderCreateGroupInitializerTest.java` | 新增消费组首次创建与已存在消费组两条回归测试，并验证同一分片只初始化一次 |
| `RedisOrderCreateRealtimeRelay.java` | 调整为先按分片阻塞读取真实消息，再为每条消息获取全局 in-flight permit；空闲分片不再预占发送容量，已读但尚未投递的记录保留在 PEL |
| `RedisOrderCreatePublisher.java` | 增加可中断的单 permit 等待入口，permit 只代表真实在途事件，不再代表空闲 Worker 的批量预算 |

### 验证结果

- `RedisOrderCreateGroupInitializerTest` 与 `RedisOrderCreatePublisherTest` 共 5 项通过，Program Reactor 编译、测试及 Spring Boot 可执行 jar 打包成功；
- 修复后 16 个分片消费组均成功初始化，未再出现 `UnsupportedOperationException`；节目 `52` 所在 shard `4` 的新事件成功完成 Kafka ACK，Consumer Group 最终 `pending=0`、`lag=0`；
- 直连服务创建订单 `702344268450160645`，约 2.5 秒后订单缓存可见；通过 Vite 前端同源代理完成登录、v5 创建和轮询，订单 `702345015933853701` 在第二次轮询即返回；
- 两笔订单均真实写入 MySQL，Kafka 消费日志记录建单事务成功且创建 DLT 为 0；验证结束后已通过业务取消接口释放测试座位。

本次不修改 Lua 锁座、限流、Kafka 投递、订单事务及前端轮询语义。

---

## 2026-08-27：修复林俊杰 JJ20 节目无法预热和下单

### 根因

- 节目 `32` 的 6 个票档在种子数据中分别声明 `500/1000` 张余票，但每个票档实际只创建了 40 个物理座位；
- v5 冷节目首次下单会校验 `ticket_category.remain_number` 是否等于可售座位事实，因票档 `34` 首先出现 `500 != 40` 而 fail-closed；请求因此停在预热阶段，尚未进入 Lua、Redis Stream 或 Kafka；
- 对全部四个节目物理分片执行同口径扫描，只有节目 `32` 的票档 `34～39` 存在该问题。

### 修改清单

| 文件 | 修改 |
| --- | --- |
| `sql/reliability/20260827_reconcile_program_32_ticket_inventory.sql` | 新增可重复执行的数据迁移；从有效物理座位推导票档总数，并按 `sell_status=1` 推导剩余数，避免执行迁移时覆盖已经售出的库存 |
| `sql/cloud/xingyan_program_0.sql` | 重建种子在导入节目 `32` 后按物理座位事实校正票档计数，避免重建 Docker 后复发 |
| `sql/reliability/README.md` | 将本迁移加入现有环境执行顺序 |

### 验证结果

- 迁移后票档 `34～39` 均为 `total_number=40`、`remain_number=40`，与各档 40 个有效可售座位一致；
- 经 Vite `5173` 同源代理、Gateway 和 v5 接口创建真实 JJ20 订单 `702350032396935173`，首次预热与提交耗时 796ms，第二次订单轮询返回成功；
- shard `0` Stream 完成 Kafka ACK，Consumer Group `pending=0`、`lag=0`，订单消费成功写入 MySQL，创建订单 DLT 为 0；
- 验证订单通过业务接口取消后，票档 `34` 从 39 恢复到 40，座位 `35291` 从 `LOCK` 恢复 `NO_SOLD` 且清空 reservationId。
