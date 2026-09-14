# 星演可靠性 SQL

当前重构以 `20260910_single_trade_stream_schema.sql` 为唯一的新环境交易库基线。它创建单库 `stellaris_trade` 及订单、订单明细、节目关联、座位交易态、账号节目购票计数、建单请求结果、Redis 状态同步命令和 Stream 失败审计表。

新环境只需执行该基线脚本。`ops/docker-compose.local.yml` 在首次创建 MySQL 数据卷时会挂载并执行它；已经存在的数据卷不会自动迁移，执行前应备份并根据实际旧表制定迁移步骤。

已经使用旧版 `20260910` 基线创建的环境，还需要执行 `20260914_stream_failure_recovery_envelope.sql`，为 Stream 异常审计补齐 `intent_id` 恢复信封。新环境不要重复执行该增量脚本。

同目录的 `20260825_*`、`20260826_*`、`20260827_*` 和 `20260901_*` 文件保留为旧 Kafka/Intent/Outbox/跨库库存方案的演进记录，不属于当前建单链路，不应在新环境按编号顺序执行。退款脚本仍只服务退款子链路，是否启用由退款实现决定。

当前运行时事实边界：Redis Lua 原子锁座并 `XADD`；order-service 用消费组直接读取 Stream；`stellaris_trade` 的本地事务完成请求幂等、最终限购、座位 CAS 和建单；支付/取消提交后通过 `d_reservation_transition_event` 重试 Redis 展示状态同步。需要监控 Stream lag、PEL、失败审计、过期关单和 Redis 同步积压。
