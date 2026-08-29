# 可靠性与并发演练手册

所有演练先复制 `ops/v5-order-body.example.json`，替换为当前数据库中真实的节目、购票人、票档和座位。原始结果写入 `output/`，结论必须同时核对 `BUSINESS_INVARIANTS.md`。

## 基线并发

```powershell
pwsh -File .\ops\Invoke-StellarisV5Load.ps1 `
  -BodyTemplate .\ops\v5-order-body.example.json -Token '<登录 token>' `
  -Requests 100 -Concurrency 100
```

依次执行 100/500/1000 请求。热点座位预期只能有一个业务成功；HTTP 2xx 只表示网关和接口可达，必须从响应业务码、订单表和 Redis owner/reservation/sold 判断业务结果。

## 故障矩阵

| 场景 | 注入时机 | 必查证据 | 通过标准 |
| --- | --- | --- | --- |
| Kafka 关闭 | Lua 锁座并写入 Stream 后暂停 Kafka 容器 | source stream、PEL、dead stream、中继指标 | Kafka 恢复后沿原 eventId 创建唯一订单 |
| 订单消费进程退出 | 消费中终止订单服务 | consumer offset、订单唯一键、DLT 审计表 | 重投不生成第二个订单；不可恢复消息可持久化 DLT |
| 支付后节目服务退出 | 模拟成功后停止节目服务 | Order/PayBill、`d_reservation_transition_event_*` | 订单与事件同事务；服务恢复后同 commandId 迁移 sold |
| 状态事件认领后退出 | Event 进入 PROCESSING 后终止订单服务 | edit_time、event_status | lease 超时转 FAILED，并幂等重试到 SUCCEEDED |
| Redis 重启 | 存在未支付订单和延迟取消任务时重启 | pending/processing 队列、订单状态、owner | DB 扫描重新补偿；不重复释放或出售 |
| 活跃锁座时预热 | reservation/owner 非空时调用预热 | ready、maintenance、owner、reservation | 预热明确失败，锁座不被清除 |
| Stream 事件永久失败 | 关闭 Kafka 直至超过重试阈值 | dead stream、原 eventId、源 Stream/PEL | 原子进入 dead；恢复后按原 eventId 重放且只创建一个订单 |
| 退款本地提交失败 | 渠道成功后、SUCCEEDED 前终止服务 | refund_intent.refund_no、退款流水 | 恢复后使用原 refundNo，渠道幂等且仅一条退款流水 |

停止容器使用 `docker compose -f ops/docker-compose.interview.yml stop <service>`，恢复使用 `start <service>`。每次只操作矩阵中明确命名的单个服务，演练结束立即恢复并等待 `docker compose ... ps` 显示 healthy。

## 结果记录

每个场景记录：时间、代码版本、命令、请求体、预期不变量、数据库/Redis/Kafka 实际状态、恢复耗时、是否人工介入。没有这些证据时，只能称为“机制已实现”，不能称为“故障演练通过”。
