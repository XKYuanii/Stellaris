# 可靠性与并发演练手册

所有演练只在可丢弃的隔离数据中进行。保存代码版本、配置、请求体、SQL/Redis 证据和恢复耗时，并逐项核对 [业务不变量](BUSINESS_INVARIANTS.md)。

## 基线

    pwsh -File .\ops\Invoke-StellarisV5Load.ps1 \
      -BodyTemplate .\ops\v5-order-body.example.json -Token '<token>' \
      -Requests 100 -Concurrency 100

HTTP 成功只表示请求到达。必须继续查询订单物化结果，并统计 CREATED、REJECTED、限流与积压拒绝。

## 必做场景

| 场景 | 注入方式 | 通过标准 |
| --- | --- | --- |
| 提交后 ACK 前退出 | 在订单事务提交后强制终止 order-service | PEL 重领；同 request/order 只有一单，额度和座位只变一次 |
| 交易库停机 | 停 MySQL 后持续发送隔离请求 | 消息留在 Stream/PEL；达到阈值后新请求在 Lua 写前拒绝；恢复后形成明确结果 |
| Redis 同步失败 | 提交取消后暂停 program-service 或 Redis | 同步记录保持可重试；恢复后座位正确释放；旧命令不能影响新归属 |
| 重复投递 | 用同一 payload/reservationId 写入 Stream 十次 | 一笔订单、一次额度增加、每个座位一次锁定 |
| 到期关单 | 创建短过期未支付订单 | 扫描任务关闭订单、释放座位、返还额度；重复扫描无副作用 |
| 多座位部分冲突 | 先占其中一个座位，再请求包含它的多座位订单 | 整单拒绝，其余座位和额度回滚 |
| 支付与关单竞争 | 同时触发模拟支付和到期扫描 | 只有 PAY 或 CANCEL 一个终态，对应库存和额度一致 |
| Redis 重建 | 准备 AVAILABLE/LOCKED/SOLD 后进入维护预热 | 重建保持交易库状态，不把锁定或已售座位变成可售 |

## 需要记录的指标

- Gateway 接单量、各层限流和舱壁拒绝；
- Lua 成功/拒绝吞吐与 P95/P99；
- Stream 长度、最老消息年龄、PEL 和 Redis 内存；
- 交易事务耗时、锁等待、连接池占用和回滚率；
- 请求进入到 CREATED/REJECTED 持久化的端到端 P95/P99；
- 故障恢复耗时和是否需要人工处理。

没有原始证据时，只能说机制已实现，不能说故障演练或生产容量已经验证。
