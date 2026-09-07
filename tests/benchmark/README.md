# V5 自动配座阶梯压测说明书

简化的 V5 HTTP 同步链路容量拐点测试请优先阅读 [V5-HTTP-CAPACITY.md](V5-HTTP-CAPACITY.md)，对应脚本为 `v5-http-capacity.jmx`、`run-v5-http-stage.ps1` 和 `run-v5-http-ladder.ps1`。

本目录同时是 JMeter 脚本、环境准备、数据初始化、接口契约、执行、恢复、正确性校验和容量分析的唯一入口。正式性能结论只能来自 `run-benchmark.ps1` 的 Non-GUI 结果；已有的 1/3/10 并发、14/14 成功仅是功能正确性证据，不是 QPS 或容量证据。

> **危险边界**：所有初始化、恢复和清理只允许作用于独立测试节目 `900000`、独立票档 `900001` 和用户 `910000000000000000..910000000000000999`。禁止在生产环境执行，禁止把参数改成真实节目后执行。

# 压测前必须补齐的信息清单

| 需要什么 | 从哪里获取 | 填到哪里 | 当前状态 |
|---|---|---|---|
| JMeter 5.6.3 `bin` 目录 | 本机解压目录 | `run-benchmark.ps1 -JMeterBin` | 已确认 `G:\study\Computer\java\apache-jmeter-5.6.3\apache-jmeter-5.6.3\bin` |
| Custom Thread Groups、Throughput Shaping Timer | JMeter Plugins Manager | `benchmark.jmx` 运行前安装 | 已确认 casutg 3.1.1、tst 2.6、perfmon 2.1 |
| JDK 17 路径 | `java -version` | `-JdkHome` | 正式机必须确认；JDK 25 会导致 Groovy 兼容问题 |
| Kafka 容器名 | `docker ps` | 四个 PowerShell 脚本的 `-KafkaContainer` | 当前确认 `stellaris-interview-kafka-1` |
| MySQL/Redis 本地测试密码 | Docker Compose/环境变量 | `-MySqlPassword/-RedisPassword` | 仓库本地默认值已配置，换环境必须覆盖；不要写入结果目录 |
| 1000 个有效 Gateway token | 登录/离线签发流程 | `users.csv` 第三列 `token` | `【待项目提供】`；当前专用用户密码为 NULL，不能登录生成 token |
| PerfMon ServerAgent 地址和端口 | 压测机/被测机部署记录 | JMX 中禁用的 `PerfMon Metrics Collector` | `【待项目提供】`；默认不启用，不影响 JMeter 主结果 |
| 稳态 SLA | 业务 SLO，例如 P99、错误率 | `CAPACITY-TEST.md` 报告判定栏 | `【待业务提供】`；文档只给默认技术门槛 |
| 300~3000 QPS 所需扩容数据 | 由 `QPS×持续时间×每请求票数` 计算 | 扩容专用节目或另建数据集，并传 `-SeatBudget/-UserPurchaseBudget` | 当前 10000 座、1000×6 限购不支持高档稳态 |
| Kafka 前缀被覆盖时的真实 topic | `prefix.distinction.name`、Kafka UI | `-KafkaTopic` | 默认源码确认 `stellaris-create_order` |
| Kafka consumer group 被覆盖时的真实值 | Order Service 配置 | `-KafkaGroup` | 默认源码确认 `create_order_data` |

## 已知信息整理

- 服务：Gateway `127.0.0.1:6085`，Program `127.0.0.1:6086`，Order `127.0.0.1:8081`。
- V5 直连：`POST /program/order/create/v5`；Gateway：`POST /stellaris/program/program/order/create/v5`。
- 真实 Header 名是 `token`，不是 `Authorization: Bearer`。隔离本机经 Gateway 验证时可显式开启 `no_verify: true`，并携带 `X-Stellaris-Demo-User-Id`；该身份可伪造，不能用于联网或生产环境，但请求仍经过 Gateway 限流和舱壁。
- 专用节目 `900000`，票档 `900001`，节目名“V5并发压测专用节目”，100×100 共 10000 座。
- 1000 个用户及 1000 个观演人；ID 大于 `2^53`，JMX 用 Groovy 生成 JSON，ID 保持字符串语义，防止 Lua `cjson` 精度回归。
- 单账号限购 6，因此一次初始化最多允许 6000 个单票成功请求。可用成功预算为 `min(10000, 1000×6)=6000`。
- Redis sale shard 为 `900000 % 16 = 0`；Stream 是 `stellaris:{sale:0}:reservation:event:stream`，group 是 `stellaris-order-relay`。
- Kafka 默认 topic 是 `stellaris-create_order`，订单 Consumer Group 是 `create_order_data`。
- 订单取消真实接口为 `POST 127.0.0.1:8081/order/cancel`；正常恢复优先逐单调用它。
- 自动配座低基数 Micrometer 指标已经存在，详见 `CAPACITY-TEST.md`。

## 2026-08-27 单链路实测基线

正式并发测试前已用 JDK 17 + JMeter 5.6.3 Non-GUI 执行 `1 thread × 1 loop`，走 Gateway V5 全链路。入口 HTTP 200、业务断言成功、耗时 1430 ms；异步只创建一笔订单 `702623377327693824` 和一个座位 `930000000000004521`，Stream/PEL=0、Kafka LAG=0。随后调用真实取消接口，订单状态变为 2，最终恢复为 MySQL 10000、Redis 10000/0/0、Stream/PEL 0、Kafka LAG 0/0/0。证据目录：`output/jmeter/single-chain-20260827-182709344/`。这仍是功能/链路证据，不是容量结论。

## 最终文件规划

```text
tests/benchmark/
├── README.md
├── INTERFACE-CONTRACT.md
├── TEST-DATA.md
├── CAPACITY-TEST.md
├── benchmark.jmx
├── users.csv
├── prepare-benchmark.ps1
├── run-benchmark.ps1
├── reset-benchmark.ps1
├── verify-reset.ps1
├── sql/
│   ├── prepare.sql
│   ├── verify.sql
│   └── force-reset.sql
└── results/
```

原有 `Prepare-StellarisV5Benchmark.ps1`、`Cleanup-StellarisV5Benchmark.ps1` 和 1/3/10 并发功能检查继续保留，新的入口脚本在其已验证逻辑上增加数据预算、Stream/PEL/Kafka 门禁和结果留档。

# 最短执行路径

以下命令从仓库根目录执行。PowerShell HTTP 调试必须使用 `curl.exe`，不要使用 `curl` 别名。

```powershell
& '.\tests\benchmark\prepare-benchmark.ps1'
& '.\tests\benchmark\verify-reset.ps1'

# 只验证 JDK/插件/CSV/预算/恢复门禁，不发送负载
& '.\tests\benchmark\run-benchmark.ps1' -TargetQps 100 -ValidateOnly

# 当前 6000 成功预算只适合低档、短采样；脚本会在超预算时拒绝执行。
& '.\tests\benchmark\run-benchmark.ps1' `
  -TargetQps 100 `
  -RampUpSeconds 10 `
  -WarmupSeconds 15 `
  -SampleSeconds 20 `
  -RampDownSeconds 5

& '.\tests\benchmark\reset-benchmark.ps1' -Mode Normal
```

`run-benchmark.ps1` 默认走 Gateway，因此 USER/PROGRAM/GLOBAL 限流和本机舱壁均在被测链路内。只定位 Program Service 时才显式使用 `-DirectProgramService`，并在报告中标记“绕过 Gateway”。

脚本一次只跑一个目标 QPS，并默认在留档后执行真实取消的 Normal reset；它不会自动启动下一档。`-KeepState` 只用于故障取证，使用后必须手工恢复。当前默认阶段下，100 QPS 预计 4250 次并通过 4800 安全预算；300 QPS 预计 12750 次，会在发请求前被拒绝。

# 环境准备

1. 启动 MySQL 8、Redis 7、Kafka 3.x、Nacos、Elasticsearch，以及 Gateway、Program、User、Order 服务。
2. 确认健康检查：

```powershell
curl.exe -fsS 'http://127.0.0.1:6085/actuator/health'
curl.exe -fsS 'http://127.0.0.1:6086/actuator/health'
curl.exe -fsS 'http://127.0.0.1:8081/actuator/health'
```

3. JMeter 5.6.3 使用 JDK 17。GUI 只用于查看/调试 JMX；正式压测只运行 `jmeter.bat -n`。
4. 安装 Custom Thread Groups 与 Throughput Shaping Timer。PerfMon 是可选增强：需要被测机先启动 ServerAgent，填写地址后再在 JMX 中启用 Collector。
5. 停止无关高负载程序；记录电源模式、CPU/内存、Docker 配额、JVM 参数。压测机和被测服务在同一台机器时，结论必须注明“load generator 与 SUT 资源竞争”。

# 数据初始化与恢复总则

- `prepare-benchmark.ps1` 只在无 owner、reservation、未支付订单时初始化，随后调用真实 `/program/data/preheat`。
- 每档压测使用独立结果目录，并在下一档前执行 `reset-benchmark.ps1 -Mode Normal`。
- Normal：发现本轮未支付订单 → 调真实取消接口 → 等异步释放 → 恢复专用座位快照 → 预热 → 调 `verify-reset.ps1`。
- Force：仅当 Normal 失败且环境已损坏时使用；必须显式传 `-ConfirmForceReset`。它会清理专用节目数据，仍会拒绝处理已支付订单。
- 只有 MySQL/Redis/Stream/PEL/Kafka/订单五类门禁全部 PASS，下一轮才可开始。

# 每轮压测后的恢复流程

## A. 正常恢复（默认且优先）

```text
遍历两个 order 库、每库四个物理 d_order 表
→ 找到 program=900000 且 order_status=1 的订单
→ 逐笔 POST 8081/order/cancel
→ 等订单状态=2、owner=0、未支付订单=0
→ 拒绝任何已支付测试订单
→ 恢复专用座位/票档、清理专用节目 Redis Key
→ 调真实 Program preheat
→ verify-reset.ps1 校验全部门禁
```

命令：

```powershell
& '.\tests\benchmark\reset-benchmark.ps1' -Mode Normal
```

正常取消本身也是正确性验证：只有订单、Redis owner/reservation、MySQL 座位/库存和异步迁移一起收敛才算成功。接口返回 `true` 不是最终恢复成功。

## B. 强制恢复（故障兜底）

> **只能用于 program=900000/category=900001 的独立本地环境，禁止生产执行。**

```powershell
& '.\tests\benchmark\reset-benchmark.ps1' -Mode Force -ConfirmForceReset
```

Force 会再次拒绝已支付订单，按 `program_id=900000` 动态删除测试订单相关表记录，执行 `sql/force-reset.sql` 恢复座位/票档/库存操作，清理该节目的 Redis Key 并重新预热。Redis Stream 是 sale shard 共享资源，脚本不会暴力删除整个 Stream；若 Stream/PEL 仍不为 0，Force 最终必须 FAIL，工程师应先查 Relay/PEL/Dead Stream，而不是删除其他节目的消息。

## 下一轮准入标准

以下必须同时满足：

```text
MySQL available seats = 10000
ticket category remain = 10000
Redis available/owner/reservation = 10000/0/0
Redis ready = 1
Stream length = 0
PEL pending = 0
Dead Stream length = 0
Kafka stellaris-create_order 的 create_order_data 每分区 LAG 连续两次为 0
上一轮无 order_status=1，且无已支付测试订单
```

`verify-reset.ps1` 任一项失败会抛错并返回失败退出状态；不要用 `-SkipKafka` 形成正式测试准入。

# 数据正确性校验

`run-benchmark.ps1` 在自动恢复前生成 `integrity.json`，要求：所有阶段的 JMeter 业务成功数 = 唯一订单号数 = MySQL 未支付订单数 = MySQL reservation_id 非空座位数 = Redis owner 数 = Redis reservation 数。任何不等都将该轮标为完整性失败，即使 JMeter 错误率是 0。`sql/verify.sql` 还可检查跨物理表订单号重复；正式报告应抽样 `/order/get` 核对订单状态和座位详情。

# Non-GUI 命令释义

入口脚本最终执行的核心命令等价于：

```powershell
& "$JMeterBin\jmeter.bat" `
  -n `
  -t '.\tests\benchmark\benchmark.jmx' `
  -l '.\tests\benchmark\results\<run>\result.jtl' `
  -e `
  -o '.\tests\benchmark\results\<run>\report'
```

- `-n`：Non-GUI 模式，正式性能测试必须使用。
- `-t`：JMX Test Plan。
- `-l`：JTL 原始样本文件。
- `-e`：测试结束后生成 HTML Dashboard。
- `-o`：HTML Dashboard 输出目录，必须是不存在或为空的目录。

# 结果追溯

每轮目录包含 JTL、HTML、`parameters.json`、开始/结束时间、Git commit（当前目录无法识别 Git 时写 `UNAVAILABLE`）、服务配置副本、JVM/机器/Docker 信息、Redis INFO、Stream/PEL、Kafka group describe、MySQL performance_schema、Prometheus 快照、JMeter 日志和 `SUMMARY.md`。密码与 token 不写入结果目录。

# 给下一个 AI 的最小上下文

这是 Spring Boot V5 自动配座完整链路压测。真实入口是 Gateway `POST :6085/stellaris/program/program/order/create/v5`，直连 Program 是 `POST :6086/program/order/create/v5`；Header 用 `token`，本地可用 `no_verify:true`。请求字段见 `INTERFACE-CONTRACT.md`。专用数据是 program `900000`、category `900001`、10000 座、1000 用户、每账号限购 6，故每轮成功预算仅 6000。Redis shard=0，Stream=`stellaris:{sale:0}:reservation:event:stream`，Stream group=`stellaris-order-relay`；Kafka topic=`stellaris-create_order`，group=`create_order_data`。正式测试必须 Non-GUI、每档独立一轮、先 `verify-reset.ps1`、后 `reset-benchmark.ps1 -Mode Normal`。高档稳态必须扩容数据，不能缩短到几秒后宣称容量。所有未知项保留 `【待项目提供】`，不得猜。
