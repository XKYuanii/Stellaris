# V4/V5 正式对照测试方法

## 两层测试

1. 同并发A/B：固定100并发，按V4→V5、V5→V4交替执行7组，使用各版本7轮中位数计算成功TPS、成功请求Avg/P99和3秒SLO达标率。
2. 同SLO容量：SLO定义为请求成功率不低于99%、成功请求P99不超过3000ms，并通过全部数据正确性门禁。V4边界使用5轮确认；V5容量材料按用户要求选取最佳有效实测，并标注为单轮最佳而非稳定容量。

## 固定请求模型

- 直连单实例Program Service `127.0.0.1:6086`，绕过Gateway；因此本测试不包含网关限流能力。
- 接口分别为`/program/order/create/v4`和`/program/order/create/v5`，两版都是一个HTTP自动配座入口，不存在“V5只测单接口、V4测全链路”的差异。
- 隔离节目`900100`、票档`900101`、10000个独立座位、5000个独立用户。
- 每线程只请求一次，每单一张票，每个请求具有唯一`requestId`。
- 节目、票档、座位和购票人缓存提前预热；HTTP超时120秒，3秒仅作为SLO判定阈值。
- JMeter Non-GUI执行；正式POST的TPS和时延只按`POST Auto Seat Comparison`样本统计，预建连GET不计入。

## 两版同步边界

- V4：HTTP进入 → 票档级JVM锁 → Redis Lua锁座 → 同步等待Kafka broker ACK → HTTP返回。
- V5：HTTP进入 → ZSET有限候选 → O(k) Redis Lua原子校验/锁座/幂等/XADD → HTTP返回。
- 两版都不把Kafka消费和MySQL落库时间计入HTTP时延；所有成功订单最终可见仍是每轮有效性门禁。

## 统一指标

- `successfulThroughputTps`：成功请求数除以正式POST从首个开始到最后一个完成的窗口。
- `successfulP99Ms`：仅针对成功正式POST计算的P99。
- `requestSuccessRatePercent`：HTTP 200、业务`code=0`且返回有效唯一订单号的比例。
- `sloAttainmentRatePercent`：成功且响应时间不超过3000ms的请求占全部正式请求的比例。
- `allOrdersVisibleObservedByMsFromBurstStart`：从首个正式请求到脚本观察到所有成功订单均在MySQL可见的保守上界；它不是单订单落库P99。
- `recoveryStatus`：订单、座位、Redis、Stream、Kafka和MySQL恢复终态。

## SLO判定

一轮只有同时满足以下条件才叫SLO通过：

```text
requestSuccessRatePercent >= 99
successfulP99Ms <= 3000
orderVisibilityStatus = PASS
recoveryStatus = PASS
唯一性、无超卖、Stream/PEL/Dead Stream、Kafka Lag门禁全部通过
```

因此“HTTP/业务请求都成功”不等于“SLO通过”。例如一轮即使100%成功，只要P99超过3秒，仍然判定SLO不通过。

## 数据正确性门禁

- 请求数等于目标线程数，请求ID全部唯一。
- 成功请求数等于唯一成功订单号数，所有成功订单最终在MySQL可见。
- 不存在重复订单、重复座位或超卖。
- Redis Stream已排空、PEL为0、Dead Stream为0。
- Kafka Lag归零。
- 恢复后10000个座位、票档余量、Redis V4/V5结构全部恢复。
- `recoveryStatus=PASS`，否则该轮不能作为有效性能证据。
