# 证据索引

## 正式结果

- `formal-100-aggregate.json`：100并发7轮交替A/B聚合数据。
- `formal-100-all-runs.csv`：14个正式单轮指标。
- `FORMAL-100-SUMMARY.md`：人类可读的正式100并发结论。
- `v5-best-1000-summary.json`：V5最佳1000并发有效轮摘要。
- `v5-best-1000-summary.json`中的容量指标仅统计1000条正式POST；完整原始JTL保留在`tests/benchmark/results/v4-v5-campaign/20260828-formal/exploration/v5-1000/result.jtl`，面试材料包不重复拷贝大体积探索日志。
- `v4-slo-20-run-1-summary.json`至`run-5`：V4 20并发5轮SLO确认摘要。
- `ONE-SHOT-RECHECK-20260829.md`：主机重启后按限定执行的四档单次复核说明。
- `recheck-v4-100-summary.json`、`recheck-v5-100-summary.json`、`recheck-v5-1000-summary.json`、`recheck-v5-1500-summary.json`：四档schema v5完整摘要。
- `recheck-v5-1500-failure-breakdown.csv`：1500档34次`Connection refused`的分类证据。
- `service-state-recheck-20260829.json`：本批次三个业务服务的PID、JVM参数和JAR哈希快照。

## JVM与环境

- `JVM-BASELINE-AND-AFTER.md`：9个IDEA JVM基线、2服务诊断迭代和3服务正式运行时对比。
- `configuration-manifest.json`：最终运行实例启动时的进程级JVM参数、JAR哈希和PID快照；其中`status=RUNNING`表示采样时状态。
- `service-state.json`：同一实例停止后的终态，`status=STOPPED`且`rollbackResult=PASS`。
- `environment-before-start.json`、`environment-after-start.json`：主机与Docker环境快照。
- `*-jvm-command-line.txt`、`*-jvm-flags.txt`：运行时JVM证据。

## 源码定位

- V4入口及策略：`damai-server/damai-program-service/src/main/java/com/damai/service/strategy/impl/ProgramOrderV4Strategy.java`。
- V4票档级JVM锁：`damai-server/damai-program-service/src/main/java/com/damai/service/strategy/BaseProgramOrder.java`第30～67行。
- V4 Redis Lua及Kafka异步创建：`damai-server/damai-program-service/src/main/java/com/damai/service/ProgramOrderService.java`第347～351、482～492行。
- V4等待Kafka ACK：同文件第733～756行。
- V5编排与有限候选：`damai-server/damai-program-service/src/main/java/com/damai/service/reference/ReferenceOrderOrchestrator.java`第65～169行。
- V5 Lua调用：`ReferenceSeatReservationService.java`第48～55行。
- V5锁座与XADD原子提交：`damai-server/damai-program-service/src/main/resources/lua/referenceReserveSeats.lua`第84～100行。
