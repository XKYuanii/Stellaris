# V5 容量测试口径

容量测试分成两个问题。

1. 生产入口容量：保留 Gateway 限流和舱壁，观察在既定保护策略下可接受多少流量。
2. 交易链路容量：使用隔离环境调高限流上限，寻找 Redis Lua、Stream 消费和 MySQL 单事务的第一个拐点。

两种结果不能混写。HTTP 受理吞吐与 MySQL 订单提交吞吐也必须分别报告。

## 阶梯

对相同数据集依次运行 50、100、200、400 并发，每档至少重复三次。每档前执行准备脚本，结束后等待 Stream 和 PEL 归零，再执行清理。容量不足时停止加压，不通过缩短测试时间隐藏积压。

## 同时采集

- Gateway 请求量、三层限流拒绝、舱壁 in-flight 与拒绝；
- Program Lua 成功、业务拒绝、积压拒绝与耗时分位数；
- Redis instantaneous_ops_per_sec、CPU、内存、Stream 长度、PEL、最老消息年龄；
- Order CREATED/REJECTED/异常审计计数；
- Hikari active/pending、MySQL TPS、事务耗时、行锁等待、死锁与慢 SQL；
- 请求进入到 t_order_request 最终状态的端到端 P95/P99。

## 拐点判定

任一条件持续出现即可判为超过当前容量：

- 成功吞吐不再随负载增长，而 P99 或积压继续增长；
- Stream/PEL 在停止压力后不能在约定时间恢复；
- MySQL 锁等待、连接等待或回滚率持续上升；
- Redis 内存或单线程 CPU 到达不可接受水位；
- Gateway 保护规则开始成为主要拒绝原因。

阈值由结果决定，不在报告前预写“单机可支撑多少 QPS”。

## 结果模板

| 项目 | 值 |
| --- | --- |
| Git commit | |
| CPU/内存/磁盘 | |
| JVM 与 GC | |
| Redis/MySQL 配置 | |
| 入口与限流配置 | |
| 用户/场次/座位/每单票数 | |
| HTTP accepted TPS | |
| MySQL CREATED TPS | |
| HTTP P95/P99 | |
| 物化 P95/P99 | |
| Lua P99 | |
| 事务 P99/锁等待 | |
| Stream 峰值/最老年龄/恢复时间 | |
| 业务不变量 | PASS/FAIL |
