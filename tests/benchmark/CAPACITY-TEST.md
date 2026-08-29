# V5 容量与拐点测试

# 1. 测试目标与边界

本方案同时报告两个不同问题，禁止混为一个“最大 QPS”：

1. **Gateway 策略容量**：保留 USER/PROGRAM/GLOBAL 限流和 200 并发舱壁，回答真实配置允许多少、拒绝是否符合预期。
2. **下游链路容量**：直连 Program Service，或把 Gateway 限流上限临时提高到目标 QPS 的 120% 以上，回答 Lua/Stream/Kafka/MySQL 在哪里出现拐点。任何临时配置必须保存到结果目录。

当前 Gateway 配置已确认：USER=3 QPS/用户、PROGRAM=1000 QPS、GLOBAL=3000 QPS、bulkhead=200 concurrent。保持原配置时，1000 QPS 以上首先看到 PROGRAM 限流是正确策略行为，不是 Redis/Kafka/MySQL 的容量拐点；1000 用户对 USER 维度的理论上限是 3000 QPS。

入口响应是“Redis 原子接纳”延迟，MySQL 建单是异步完成。报告必须同时给出：

- admission：JMeter V5 create 的 P50/P90/P95/P99、错误率、实际吞吐。
- pipeline：Stream wait、Kafka send、Kafka LAG、订单 Consumer/MySQL 建单和 `stellaris_order_end_to_end_seconds`。

# 2. 为什么 10000 座不能跑既定长阶梯

当前每请求 1 张票、1000 用户、每账号限购 6，所以每轮成功预算：

```text
seat budget       = 10000 / 1 = 10000 requests
account budget    = 1000 × 6 / 1 = 6000 requests
hard budget       = min(10000, 6000) = 6000 requests
safe budget (80%) = 4800 requests
```

线性 ramp 的预计请求数：

```text
targetQps × (rampUp/2 + warmup + sample + rampDown/2)
```

例如原提议的 `3000 QPS × 3 min = 540000`，还没算预热/ramp，就已经是座位数的 54 倍、账号预算的 90 倍。缩成 2 秒虽然不耗尽库存，但不是稳态容量测试。

当前 fixture 唯一可接受的短基线示例：

```text
100 QPS: ramp 10s + warmup 15s + sample 20s + down 5s
预计 = 100 × (5 + 15 + 20 + 2.5) = 4250 < 4800
```

它只能给低档基线。300 QPS 使用相同时长预计 12750，脚本必须拒绝。

解决方案按推荐顺序：

1. **扩大独立测试数据（推荐）**：每个 QPS 档单独一轮并恢复。数据量至少为公式结果的 1.2 倍，同时提高专用节目的账号限购；这是最不污染完整链路的方案。
2. 分轮、缩短到仍能形成平台的采样时间：可作工程诊断，但少于 30 秒正式采样不得给稳定容量结论。
3. 边压边真实取消：会把取消、库存迁移和 DB 写入混进系统负载，只能命名为“购票+取消混合场景”，不能代表纯购票容量。
4. 单测锁座入口并立即释放：需要项目提供专用测试接口，目前不存在，记为 `【待项目提供】`；结果只代表锁座，不代表完整订单链路。
5. 重复同一个 requestId 不可用：这会压中幂等回执快路径，而不是新订单链路。

若采用 30s ramp、60s warmup、120s sample、15s down，3000 QPS 一档预计 607500 次，含 20% 裕量需约 729000 座位/账号购票额度。这个数字说明本地 10000 座不适合长高档，而不是建议盲目向本机塞 73 万座。

# 3. 推荐阶梯

## 3.1 当前 10000 座 fixture

- 已完成：1×1 单链路功能检查，不能作为容量。
- 下一步仅建议：`50 QPS` 和 `100 QPS` 两个短基线，各自独立初始化/恢复。
- 不建议在当前 fixture 上运行 300 QPS 以上并报告容量。

## 3.2 扩容 fixture 后的正式阶梯

每个档位独立运行、独立目录、独立恢复：

```text
100 → 300 → 500 → 800 → 1000 → 1500 → 2000 → 2500 → 3000 QPS
```

每档：ramp-up 30s、warm-up 60s、正式 sample 120s、ramp-down 15s。低性能本地机可先用 sample 60s 筛查，但候选拐点必须复测 120~300s。发现拐点在 `[L,H]` 后增加：

```text
L, L+200, L+400, ... , H
```

例如 1800/2000 稳定而 2500 恶化，则复测 2000/2200/2400/2600；每个点至少重复 3 次，使用中位结果，避免把偶发 GC/后台任务当拐点。

# 4. JMeter Test Plan 设计

`benchmark.jmx` 使用 JMeter 5.6.3 和已确认安装的 Custom Thread Groups 3.1.1、Throughput Shaping Timer 2.6。正式运行没有 View Results Tree 等 GUI Listener。

| 组件 | 放置位置 | 配置 | 为什么 |
|---|---|---|---|
| User Defined Variables | Test Plan | host/port/path/program/category/noVerify/阶段秒数均由 `-J` 注入 | 同一 JMX 适配 Gateway、直连和不同档位 |
| setUp Thread Group | Test Plan 下 | 1×1 JSR223，记录统一 `benchmarkStartMillis` | 所有线程用同一时间轴标记 ramp/warmup/sample/down |
| CSV Data Set Config | 主线程组上方 | `users.csv`，All threads，recycle=true | 1000 用户全局轮换，避免同账号热点 |
| HTTP Request Defaults | Test Plan | keep-alive、connect 3s、response 10s | 连接复用符合真实客户端；超时有界 |
| HTTP Header Manager | Test Plan | JSON、`no_verify`、`token` | 使用项目真实 Header，不使用 Bearer 猜测 |
| Concurrency Thread Group | 主负载 | threads 由 Little 定律估算，hold=各阶段总和 | 线程只提供并发能力，不用线程数冒充 QPS |
| Throughput Shaping Timer | create sampler 作用域 | 线性升至 target、warmup/sample 保持、最后下降 | 控制目标请求到达率并形成稳定平台 |
| JSR223 PreProcessor | create 前 | UUID、阶段标记、Groovy JsonOutput | requestId 每请求唯一；64 位 ID 不经 JS Number |
| HTTP Request | 主事务 | POST V5 create | 正式 QPS 只统计这一个主采样器 |
| JSON Extractor | create 后 | `$.data` → orderNumber | 追溯订单号；不参与候选内部重试 |
| JSR223 Assertion | create 后 | HTTP+code+纯数字订单号 | HTTP 200 业务失败必须计错 |
| Backend Listener | Test Plan | 默认 disabled，填 Influx/Graphite 后启用 | 可实时看 QPS/线程；未知后端不能硬编码 |
| PerfMon Metrics Collector | Test Plan | 默认 disabled，ServerAgent `【待提供】` | 未配置时不让监控失败污染主测试 |

线程数按 Little 定律预估：

```text
threads = ceil(targetQps × expectedP99Seconds × 1.5)
```

如果目标 1000 QPS、预估 P99=500ms，则约 750 线程。若实际吞吐达不到目标且客户端 CPU/GC/网络先满，说明 JMeter 成了瓶颈：拆分负载机或提高线程/堆，不能把它误判为服务容量。

`users.csv` 是 All Threads 共享游标。每个循环新建 UUID；下一次独立请求重新生成。intentId/orderNumber/eventId 由服务器生成，候选最多 5 次的内部重试自动保持不变。JMeter 的 HTTP 自动业务重试保持关闭。

# 5. 每轮执行顺序

```text
服务/容器健康
→ verify-reset.ps1 必须 PASS
→ 保存 before 指标
→ ramp-up
→ warm-up（不用于正式 percentile 结论）
→ sample（正式统计窗口）
→ ramp-down
→ 等 Stream/Kafka/MySQL 收敛
→ 保存 after 指标/JTL/HTML/SUMMARY
→ reset-benchmark.ps1 -Mode Normal
→ verify-reset.ps1 必须 PASS
```

GUI 只打开 JMX 检查组件。正式命令由 `run-benchmark.ps1` 生成，核心参数是 `-n -t -l -e -o`，含义见 README。

# 6. 观测指标

## 6.1 JMeter

正式 sample 窗口至少保存：实际 throughput、Average、P50/P90/P95/P99/Max、错误率、成功/失败数、active threads。主判断优先 P95/P99、错误率、实际吞吐。报告同时列目标 QPS 和实际 QPS，不能用目标替代实际。

## 6.2 自动配座 Micrometer（已存在）

```text
stellaris_auto_seat_request_total
stellaris_auto_seat_conflict_total{code="SEAT_UNAVAILABLE|SEAT_OWNED|..."}
stellaris_auto_seat_retry_total{code="..."}
stellaris_auto_seat_retry_count_total{retries="0|1|2|3|4"}
stellaris_auto_seat_final_failure_total{reason="..."}
```

这些 label 均是低基数；禁止加入 userId/requestId/orderNumber/seatId。

窗口统计使用 Prometheus `increase(metric[窗口])`。平均重试次数：

```text
sum by () (increase(stellaris_auto_seat_retry_count_total[2m]) * on(retries) group_left scalar(retries))
/ sum(increase(stellaris_auto_seat_retry_count_total[2m]))
```

实际 PromQL 不能直接把 label 转数值，推荐在报告脚本中按 `retries` label 做加权。现有指标能得到“完成时 retryCount 分布”和冲突尝试数，但不能 100% 精确区分“首次成功”与“零重试最终失败”。若专项报告必须严格给首次成功/发生冲突请求数，应增加低基数指标：

```text
stellaris_auto_seat_request_outcome_total{outcome="success|failure",retries="0|1|2|3|4",conflicted="true|false"}
```

这是 `【待项目增加】`，不得拿冲突尝试数冒充冲突请求数。

## 6.3 Redis

每轮 before/after 保存：

```powershell
docker exec stellaris-interview-redis-1 redis-cli -a redis123 INFO stats
docker exec stellaris-interview-redis-1 redis-cli -a redis123 INFO memory
docker exec stellaris-interview-redis-1 redis-cli -a redis123 INFO clients
docker exec stellaris-interview-redis-1 redis-cli -a redis123 INFO persistence
docker exec stellaris-interview-redis-1 redis-cli -a redis123 INFO commandstats
docker exec stellaris-interview-redis-1 redis-cli -a redis123 INFO cpu
```

关注 ops/s、commands、clients/blocked、memory/peak/fragmentation、hits/misses、rejected、evicted 和 CPU。还需记录 available/owner/reservation、XLEN、XPENDING。

## 6.4 Kafka

```powershell
docker exec stellaris-interview-kafka-1 `
  /opt/kafka/bin/kafka-consumer-groups.sh `
  --bootstrap-server localhost:9092 `
  --group create_order_data `
  --describe
```

记录 CURRENT-OFFSET/LOG-END-OFFSET/LAG。压力中瞬时增长、停止后归零属于可恢复积压；连续多个采样点单调增长或停止后仍不回落，说明消费/建单低于生产。

## 6.5 MySQL 8 / performance_schema

压测前先确认 `performance_schema=ON`，不要在正式窗口执行会产生巨大开销的全表分析。可直接执行：

```sql
SELECT DIGEST_TEXT, COUNT_STAR,
       ROUND(SUM_TIMER_WAIT/1e12,3) total_s,
       ROUND(AVG_TIMER_WAIT/1e9,3) avg_ms,
       SUM_ROWS_EXAMINED, SUM_ROWS_AFFECTED
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME LIKE 'stellaris_%'
ORDER BY SUM_TIMER_WAIT DESC LIMIT 30;

SELECT OBJECT_SCHEMA, OBJECT_NAME, COUNT_READ, COUNT_WRITE,
       ROUND(SUM_TIMER_READ/1e12,3) read_s,
       ROUND(SUM_TIMER_WRITE/1e12,3) write_s
FROM performance_schema.table_io_waits_summary_by_table
WHERE OBJECT_SCHEMA LIKE 'stellaris_%'
ORDER BY (SUM_TIMER_READ+SUM_TIMER_WRITE) DESC LIMIT 30;

SELECT * FROM performance_schema.data_lock_waits LIMIT 100;

SELECT EVENT_NAME, COUNT_STAR, ROUND(SUM_TIMER_WAIT/1e12,3) total_s
FROM performance_schema.events_waits_summary_global_by_event_name
WHERE EVENT_NAME LIKE 'wait/lock/%'
ORDER BY SUM_TIMER_WAIT DESC LIMIT 30;

SHOW GLOBAL STATUS WHERE Variable_name IN
('Threads_connected','Threads_running','Connections','Aborted_connects',
 'Innodb_row_lock_current_waits','Innodb_row_lock_time','Innodb_row_lock_waits');
```

连接池指标从 Actuator 记录 Hikari active/idle/pending/max；出现 pending 且连接数到 max，先判为连接池/DB 排队，不要只看数据库 CPU。

## 6.6 JVM

Gateway、Program、Order 分别记录 CPU、Heap、Young/Full GC、pause、线程数和 RUNNABLE/WAITING/BLOCKED。候选拐点档开启 JFR 60~120s，查看热点方法、对象分配、锁竞争；JFR 本身有开销，所有对比档保持同样设置。JMeter JVM 也要单独看 CPU/heap/GC，防止负载机成为瓶颈。

# 7. 自动配座冲突压力测试

目的只验证 requestId 打散与最多 5 组候选，不用于宣称最高 QPS。使用独立节目/票档，缩小热门区域并固定同一票档，提高并发；不要在容量基线节目上边缩库存边比较。

建议三档可售座位/并发比：4:1、2:1、1.2:1，每档保持相同请求数和 QPS，至少重复 3 次。记录总请求、retryCount 0/1/2/3/4、最终失败、SEAT_UNAVAILABLE/SEAT_OWNED 冲突尝试数和平均重试。严格“首次成功/发生冲突请求”需要上一节建议的新 outcome 指标。

对比表：

```text
版本 | 请求 | 成功 | 最终失败 | 冲突尝试/请求 | 平均重试 | retry=1 | retry=2 | retry=3~4
旧版 | ...
新版 | ...
```

旧版和新版必须使用相同数据快照、随机请求数、QPS、JVM/容器配额；否则不能归因于候选打散优化。

# 8. 容量拐点判定

默认技术门槛必须再与业务 SLA 对齐。一个档位进入“稳定区”要求：

- 正式窗口实际 QPS ≥ 目标的 95%，且窗口后半段无下降趋势。
- 业务错误率 ≤ 0.1%（限流实验的预期拒绝单列，不混入后端错误）。
- P99 无持续爬升；同档重复测试离散度可接受。
- 压力停止后 Kafka LAG/Stream PEL 在约定恢复时间内归零。
- CPU 不长期 >85%，无 Full GC 风暴，线程池/连接池无持续 pending。

满足任一项即进入候选拐点：

- 目标 QPS 增加而实际 QPS 增幅 < 前一档增量的 50%。
- 实际 QPS 增加 ≤10%，但 P99 增加 ≥50%。
- 错误率连续两个采样周期上升并超过 SLA。
- Kafka LAG/Stream PEL 连续增长，停止后不能恢复。
- Gateway 舱壁、Tomcat、Redis pool、Kafka inflight、Hikari 开始持续排队。
- Redis/MySQL/JVM CPU 长期接近饱和或 GC pause 显著恶化。

稳定容量取“拐点前最后一个连续满足全部稳定条件、且重复测试通过”的档位，不取偶然成功的最高点。

# 9. 瓶颈归因速查

| 最先恶化的证据 | 优先归因 |
|---|---|
| Gateway 429/限流指标，后端资源平稳 | 限流策略 |
| Gateway bulkhead rejected/active=200，后端未满 | 本机并发舱壁 |
| Program P99 上升、Redis latency/CPU/blocked 恶化 | Redis/Lua/连接池 |
| Stream wait/inflight 上升，Kafka send 变慢 | Relay/Kafka producer |
| Kafka LAG 增长，Order Consumer CPU/Hikari pending 上升 | Consumer/MySQL 建单 |
| MySQL lock waits/慢 SQL/Threads_running 上升 | SQL、锁或 DB 连接容量 |
| JVM CPU/GC/BLOCKED 先恶化 | 应用热点、分配或锁竞争 |
| 目标达不到且 JMeter CPU/GC/active threads 满 | 负载机，不是 SUT |

# 10. 最终报告模板

```text
稳定容量：约 XXXX QPS（Gateway 策略 / 下游链路二选一并写清）

容量拐点：XXXX ~ XXXX QPS

正式窗口：YYYY-MM-DD HH:mm:ss ~ HH:mm:ss
重复次数：N

主要现象：
- 实际吞吐：X → Y QPS（目标 A → B）
- P95/P99：X/Y ms → A/B ms
- 错误率：X% → Y%
- Kafka LAG：0 → N，停止后 T 秒恢复/未恢复
- CPU/GC/连接池：...

瓶颈：XXXX

证据：
1. 第一个恶化指标及时间线
2. 同时保持正常、可排除的层
3. 复测结果和配置一致性

限制：本机同机压测/数据规模/JFR 开销/限流配置等
```
