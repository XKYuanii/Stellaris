# V5 单交易库基准与正确性测试

该目录只面向当前 Lua + Redis Stream + stellaris_trade 链路。历史 V4/Kafka 测量结果不属于当前架构，不能当作当前版本容量。

## 固定测试数据

- programId：900000
- ticketCategoryId：900001
- 10000 个静态座位
- 5000 个独立用户与购票人
- Redis 销售分片：0
- Stream：stellaris:{sale:0}:reservation:event:stream
- Consumer Group：stellaris-order-service
- 交易库：stellaris_trade

## 准备

先启动 Compose 基础设施以及 User、Order、Program、Gateway 服务，然后执行：

    .\tests\benchmark\Prepare-StellarisV5Benchmark.ps1

脚本拒绝清理存在未支付或已支付订单的测试场次。它只适用于明确可丢弃的 program 900000，写入静态节目/用户数据，清理该场次旧交易事实，调用 Program 预热，并核对交易库存与 Redis ready。

## 功能回归

先独立验证真实 MySQL 上的 DDL、三个幂等唯一键、限购边界、库存快照 upsert、座位 CAS 和多座事务回滚：

    .\tests\correctness\Invoke-TradeSqlSemantics.ps1

该脚本只要求 Compose 中的 MySQL 正常运行，不依赖 Java 服务，执行完会清理隔离的 `990000001` 场次数据。

    .\tests\benchmark\Invoke-StellarisV5FunctionalCheck.ps1
    .\tests\correctness\Invoke-StellarisV5Correctness.ps1

前者执行 1/3/10 并发基础检查；后者验证 Gateway 内部接口隔离、请求幂等、单交易库事务、Stream/PEL 收敛、用户归属和取消后的 Redis 同步。

## 100 用户样本

    .\tests\benchmark\run-100-users.ps1

它使用 v5-100-users.jmx，通过 Gateway 请求并保存 JTL/HTML。该样本只能用于验证测试链和观察趋势，不能直接写成生产容量。

## 容量阶梯

使用同一份 JMX 分别测量 Program 直连入口与 Gateway 公开入口，每档同步释放 50、100、200、400 个一次性请求，并重复三轮：

    .\tests\benchmark\Invoke-StellarisV5CapacityLadder.ps1

只测交易链路时可以缩小入口范围；冒烟时也可以缩小档位和轮数：

    .\tests\benchmark\Invoke-StellarisV5CapacityLadder.ps1 -Entries direct -Stages 50 -Repeats 1

每轮都会重建隔离数据、采样 Stream/PEL、等待消费归零，并核对订单、唯一订单号、锁定座位、唯一座位、账号购票计数、Redis owner 和余票守恒。输出目录包含逐请求 JTL、JMeter 日志、Stream 采样和 `summary.csv`。

`completionThroughput` 是一批同步请求的完成窗口速率，只能描述瞬时受理，不能写成持续 TPS。`drainAfterHttpMs` 是 JMeter 退出后等待 Stream/PEL 归零的时间，也不能当作单笔物化延迟。

## 清理

    .\tests\benchmark\Cleanup-StellarisV5Benchmark.ps1

脚本先通过业务接口取消未支付订单，等待 Redis 同步完成，再删除隔离场次的交易历史并重新预热。发现已支付订单时拒绝自动清理。

## 有效报告要求

报告必须记录 Git 版本、硬件、JVM、Redis/MySQL 配置、入口、用户数、座位数、每单票数、限流阈值、CREATED/REJECTED 分布、Lua P99、物化端到端 P99、Stream/PEL、MySQL 锁等待和恢复时间。没有这些数据时不要引用单个 TPS 数字。
