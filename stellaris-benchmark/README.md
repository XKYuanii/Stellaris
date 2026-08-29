# stellaris-benchmark

此模块不把压测工具或 Redis 环境写死到业务服务。执行器必须遍历 `SeatReservationBenchmarkPlan` 中的四类实现和场景，并输出到 `docs/benchmark-results/`：吞吐、P50/P95/P99、Lua 时长、冲突重试、拒绝率、重复售票、不变量、Redis CPU、环境与原始数据。

在本地 Redis/Cluster 未启动时，禁止生成“性能结论”。
