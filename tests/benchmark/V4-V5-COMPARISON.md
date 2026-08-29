# V4 / V5 自动配座对比测试

本测试只用于本地面试项目演示。它使用独立节目 `900100`、票档 `900101`、10,000 个座位和现有 1,000 个压测用户，不使用 token，直接请求 Program Service `6086`。

当前主测试节目 `900000` 的座位 ID 大于 JavaScript/Lua 可精确表示的整数上限 `2^53`。V4 Lua 会用 `cjson` 把座位 JSON 中的数字解析为浮点数，因此不能拿它做公平对比。本夹具的座位 ID 范围是 `9300000000000` 至 `9300000009999`，同时兼容 V4 和 V5。

## 首次准备

在项目根目录用 Windows PowerShell 运行：

```powershell
& '.\tests\benchmark\prepare-v4-v5-comparison.ps1'
```

脚本会创建隔离节目数据，预热 V4 旧 Redis Hash 与 V5 ZSET/Hash 快照，并检查 MySQL、两套 Redis 库存、Stream、PEL 和 Kafka LAG。

## 单次运行

先各跑 1 个用户确认链路：

```powershell
& '.\tests\benchmark\run-v4-v5-comparison.ps1' -Version V4 -Threads 1
& '.\tests\benchmark\run-v4-v5-comparison.ps1' -Version V5 -Threads 1
```

再使用相同并发进行对比：

```powershell
& '.\tests\benchmark\run-v4-v5-comparison.ps1' -Version V4 -Threads 10
& '.\tests\benchmark\run-v4-v5-comparison.ps1' -Version V5 -Threads 10
```

每个线程只发一个请求，所有线程由 Synchronizing Timer 同时释放。每个请求读取不同的 `userId/ticketUserId` 并生成独立 UUID。结果保存在 `tests/benchmark/results/comparison/`。

运行脚本默认会分批等待所有成功返回的订单在 MySQL 可见（SQL 通过 stdin 传给 MySQL，避免 Windows 命令行过长），然后调用真实取消接口，按节目精确移除 Redis 延迟取消任务，验证 V4/V5 Redis 库存都恢复，再清理测试元数据并重新预热。因此下一轮可以重复执行。

## 指标解释

- `actualQps` 是这一批突发请求的完成吞吐，不是长期稳定容量。
- `jmeterRequestFailureRatePercent` 表示 HTTP/业务断言未成功率，不等同于技术异常率。具体原因见每轮的 `failure-breakdown.csv`，按 `businessCode + businessMessage` 汇总。
- `P95/P99` 只有样本数足够时才有参考意义，1 或 10 用户只用于链路和初步对比。
- V4 HTTP 返回代表 Redis 锁座并同步等待 Kafka broker ACK；V5 HTTP 返回代表 Redis Lua 锁座并写入 Redis Stream。两者异步边界不同，因此还要看 `mysqlVisibilityAfterJMeterMs`，不能只比较 HTTP 延迟。
- 100 用户也只是面试演示型并发测试，不应表述为系统稳定承载 100 QPS。

## 恢复

正常恢复（优先）：

```powershell
& '.\tests\benchmark\reset-v4-v5-comparison.ps1' -Mode Normal
```

仅当隔离夹具损坏且真实取消不能工作时，才允许：

```powershell
& '.\tests\benchmark\reset-v4-v5-comparison.ps1' -Mode Force -ConfirmForce
```

强制恢复会删除节目 `900100` 的测试订单历史。禁止在生产环境或非隔离节目使用。
