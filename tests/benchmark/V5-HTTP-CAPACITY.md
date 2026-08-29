# V5 下单接口 HTTP 容量拐点测试

## 热路径前提

正式阶梯测试会先执行 `prepare-v5-hot-cache.ps1`，为1000个独立压测用户写入观演人缓存。缓存值只包含 Program Service 校验必需的 `id` 和 `userId`，不包含姓名、证件号等个人信息。

每档测试都会记录 User Service `/ticket/user/list` 的 Actuator 计数器。计数增量必须为0，否则该档混入了同步 User RPC/MySQL 冷路径，脚本会判定测试无效。

单独执行缓存准备与核对：

```powershell
& '.\tests\benchmark\prepare-v5-hot-cache.ps1'
```

阶梯脚本会自动执行该步骤，不需要手工重复执行。

## 测试范围

目标接口：

```text
POST http://127.0.0.1:6086/program/order/create/v5
```

只测从 Program Service 收到 HTTP 请求到返回 HTTP 响应的同步链路。JMeter 的 TPS、Average、P95、P99 均不包含 Kafka Consumer 和 MySQL 异步落库时间。

JMeter 完成采样并写出 `http-summary.json` 后，恢复脚本才会等待成功订单可见、调用真实取消接口并校验状态。恢复时间单独写入 `recovery.json`，不计入 HTTP 指标。

本测试直连 Program Service，不经过 Gateway。当前无 token 测试经过 Gateway 时会被识别成同一匿名用户，测到 USER 限流而不是 V5 下单接口容量。

当存在连接失败时，`throughputTps` 会包含快速失败请求，不能作为有效业务吞吐。此时应读取 `successfulThroughputTps`、`successfulAverageMs`、`successfulP95Ms` 和 `successfulP99Ms`。

## 两种连接模式

默认模式保留原始口径：所有线程同时建立新连接并下单，用于测试新连接突发接入能力。

热路径预连接模式：线程先在指定时间内访问 `/actuator/info` 建立各自的 Keep-Alive 连接，再同步发送V5请求。预连接采样不进入V5的TPS和响应时间统计。

```powershell
& '.\tests\benchmark\run-v5-http-ladder.ps1' `
  -Preconnect `
  -PreconnectRampSeconds 5 `
  -Stages 50,100,200,300,400,500
```

## 2026-08-28 本机预连接实测

结果目录：`results/http-capacity/preconnected-ladder-20260828-091724/`

| 并发 | 成功率 | 成功 TPS | 成功 Avg | 成功 P95 | 成功 P99 |
|---:|---:|---:|---:|---:|---:|
| 100 | 100% | 340.14 | 221.85ms | 286ms | 292ms |
| 200 | 100% | 505.05 | 295.58ms | 388ms | 392ms |
| 300 | 100% | 484.65 | 421.40ms | 575ms | 580ms |
| 400 | 100% | 455.58 | 587.30ms | 821ms | 844ms |
| 500 | 100% | 373.13 | 864.62ms | 1227ms | 1253ms |

所有V5采样的 Connect Time 都是0ms，证明下单请求复用了预连接。500并发以内没有业务失败、技术失败或User Service观演人RPC。吞吐在200并发达到约505 TPS，之后不再增长且尾延迟持续上升，因此本轮业务处理容量拐点在200～300并发，而不是原新连接测试得到的300～325接入拐点。

扩展到1000并发后的结果：

| 并发 | 成功率 | 成功 TPS | 成功 Avg | 成功 P95 | 成功 P99 |
|---:|---:|---:|---:|---:|---:|
| 600 | 100% | 472.07 | 1016.88ms | 1168ms | 1194ms |
| 800 | 100% | 358.26 | 1650.38ms | 2178ms | 2211ms |
| 1000 | 100% | 436.30 | 1603.08ms | 2130ms | 2196ms |

1000并发仍能全部成功和最终恢复，但吞吐始终在约350～505 TPS波动，继续增加同步并发只会增加排队时间，不能把业务处理速率提升到1000 TPS。该结论只适用于V5完整下单写链路，不能与纯Caffeine/Redis/MySQL缓存查询接口的数万QPS直接比较。

## 请求与结果分类

Headers：

```text
Content-Type: application/json;charset=UTF-8
no_verify: true
```

每个线程发送：

```json
{
  "requestId": "每次请求独立 UUID",
  "programId": "900100",
  "userId": "从 users.csv 读取",
  "ticketUserIdList": ["从 users.csv 读取"],
  "ticketCategoryId": "900101",
  "ticketCount": 1
}
```

成功条件是 HTTP 200、响应 `code=0`、`data` 是数字订单号。结果分为：

- `SUCCESS`：HTTP 与业务均成功。
- `BUSINESS`：HTTP 200，但业务 `code != 0`，按 `code + message` 汇总。
- `TECHNICAL`：连接失败、HTTP 非 200、无效 JSON、成功响应缺少订单号。

JMeter 内建 `Err` 是业务失败和技术异常的总和，因此汇总字段称为 `jmeterFailureRatePercent`，不能直接称为系统异常率。详细原因查看 `failure-breakdown.csv`。

## 独立测试数据

```text
节目：900100
票档：900101
座位：10000
测试用户：1000
每个请求购买：1 张
```

单档最大 500 用户，只消耗 500/10000 个座位，并且每档结束后恢复。首次准备执行：

```powershell
& '.\tests\benchmark\prepare-v4-v5-comparison.ps1'
```

## JMeter 结构

```text
Test Plan
├── HTTP Request Defaults
├── Header Manager
├── CSV Data Set Config
├── Thread Group（-Jthreads）
│   ├── Synchronizing Timer（整档同时释放）
│   ├── JSR223 PreProcessor（UUID 与 JSON Body）
│   └── POST V5 Auto Seat HTTP
│       └── JSR223 Assertion（SUCCESS/BUSINESS/TECHNICAL）
```

CSV 全局共享、不循环，每个线程只循环一次，同档中不会重复用户。

## 运行命令

正式测试必须使用 Non-GUI。单档：

```powershell
& '.\tests\benchmark\run-v5-http-stage.ps1' -Threads 50
```

默认完整阶梯：

```powershell
& '.\tests\benchmark\run-v5-http-ladder.ps1'
```

自定义阶梯：

```powershell
& '.\tests\benchmark\run-v5-http-ladder.ps1' -Stages 50,100,150,200,250,300
```

底层等价命令：

```powershell
& 'G:\study\Computer\java\apache-jmeter-5.6.3\apache-jmeter-5.6.3\bin\jmeter.bat' `
  -n `
  -t '.\tests\benchmark\v5-http-capacity.jmx' `
  '-Jthreads=100' `
  '-JdataFile=G:\study\Computer\java\stellaris-platform\tests\benchmark\users.csv' `
  '-Jsample_variables=requestId,userId,orderNumber,failureType,businessCode,businessMessage' `
  -l '.\result.jtl' `
  -e `
  -o '.\report'
```

## 每轮恢复

自动流程：

```text
HTTP 采样结束并固定统计
→ 等待成功订单可见（不计入 HTTP 指标）
→ 调用真实 /order/cancel
→ 按 programId 删除压测延迟取消任务
→ 校验 MySQL、Redis、Stream、PEL、Kafka LAG
→ 重新预热下一档
```

手动正常恢复：

```powershell
& '.\tests\benchmark\reset-v4-v5-comparison.ps1' -Mode Normal
```

仅在隔离环境、真实取消彻底不可用时：

```powershell
& '.\tests\benchmark\reset-v4-v5-comparison.ps1' -Mode Force -ConfirmForce
```

## 结果与拐点

```text
results/http-capacity/ladder-yyyyMMdd-HHmmss/
├── ladder-summary.csv
├── ladder-summary.json
└── 0050-users/
    ├── result.jtl
    ├── jmeter.log
    ├── report/index.html
    ├── http-summary.json
    ├── failure-breakdown.csv
    └── recovery.json
```

- 稳定档：成功率至少 99%，技术异常为 0，TPS 随线程增加，P95/P99 没有突增。
- 疑似拐点：TPS 增幅明显变小且 P99 大幅上升，或首次持续出现技术异常。
- 业务保护点：出现限流/舱壁业务码，但服务仍正常返回 JSON。
- 选座冲突：必须出现真实 `SEAT_UNAVAILABLE/SEAT_OWNED` 对应的 `code/message`，不能用 JMeter Err 猜测。

同步突发并发不等同于持续 QPS。修复 Elasticsearch I/O 线程配置并重启 Program Service 后，应从 50 档重新执行，不能混用重启前后的结果。
