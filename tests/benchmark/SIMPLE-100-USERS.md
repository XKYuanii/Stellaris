# 100 用户并发测试：最简用法

这个脚本从 1000 个测试用户中取前 100 个不同用户，不获取 token，直接请求 Program Service `127.0.0.1:6086/program/order/create/v5`。100 个线程在同步栅栏处集合后一起发送，每人只请求一次 V5 自动配座。

不经过 Gateway 的原因：无 token 请求经过 Gateway 时无法生成可信的用户 Header，所有用户都会落入同一个 `anonymous` USER 限流桶；当前桶容量为 3，因此 100 人突发会得到 3 个成功和 97 个 HTTP 429。直连 Program Service 才能在“不获取 token”的前提下验证 100 个不同用户的购票主链。

仓库中已经生成了 1000 条 `users.csv`。先保证 Gateway、Program、Order 和 Docker 容器已启动，然后从仓库根目录直接运行：

```powershell
& '.\tests\benchmark\run-100-users.ps1'
```

只有 `users.csv` 被删除或测试数据需要重新初始化时，才先运行：

```powershell
& '.\tests\benchmark\prepare-benchmark.ps1'
```

输出会直接显示：成功/失败数、错误率、这次实际 QPS、Average、P95、P99、Max，以及 HTML 报告路径。结果目录在：

```text
tests/benchmark/results/simple-100-users-时间/
```

默认测试结束后走真实 `/order/cancel` 取消本轮订单并恢复 10000 座。调试时可用 `-KeepOrders` 暂时保留订单，但下一次测试前必须执行：

```powershell
& '.\tests\benchmark\reset-benchmark.ps1' -Mode Normal
```

注意：这是“100 用户同时抢票的一次突发测试”，实际 QPS 是 100 个请求在本次完成时间内形成的吞吐，不等于稳定维持的 100 QPS，也不能单独作为系统最大容量结论。面试时可以这样表述：

> 我先用 100 个独立测试账号做同步并发抢票，每个账号一个 requestId、一个观演人、购买一张票；JMeter 校验 HTTP 和业务 code，并统计 P95/P99。下单后再通过真实取消接口回收座位，确认 MySQL、Redis、Stream、Kafka 都恢复到初始状态。
