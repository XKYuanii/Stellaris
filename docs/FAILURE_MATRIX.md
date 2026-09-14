# 故障闭环矩阵

| 故障点 | 恢复行为 | 当前实现 |
| --- | --- | --- |
| Gateway 本机并发已满 | 舱壁快速拒绝，不占用下游连接 | 已实现 |
| Redis 下单令牌桶或锁座不可用 | 下单 fail-closed，不绕过 Redis 写交易库 | 已实现 |
| Stream 达到接单阈值 | Lua 在库存修改前拒绝 ORDER_BACKLOG_LIMIT | 已实现，阈值待压测 |
| Lua 后请求进程退出 | 预占与 XADD 同脚本，订单服务继续处理 | 已实现，待 kill 演练 |
| 消费者读取后退出 | 记录留在 PEL，超时后 XCLAIM | 已实现，待多实例演练 |
| MySQL 提交后 ACK 前退出 | 重领重复消费，由请求唯一键和座位 CAS 吸收 | 已实现，单元测试覆盖幂等核心 |
| 交易库停机 | 消费失败不 ACK，消息保留 Pending；入口由积压阈值保护 | 已实现，待停库演练 |
| 永久毒消息 | 先写 d_order_stream_failure 再 ACK，可按原 payload 重放 | 已实现 |
| 支付与到期关单竞争 | NO_PAY -> PAY/CANCEL 条件更新只允许一个赢家 | 已实现 |
| 订单提交后 Redis 同步失败 | 同事务的 d_reservation_transition_event 退避重试 | 已实现 |
| 到期任务进程退出 | 下次按 (order_status, expire_time, id) 继续扫描 | 已实现 |
| Redis 座位数据丢失 | 关闭 ready；维护窗口从交易库权威状态重建 | 已实现受控重建，待故障演练 |
| Redis 主节点切换 | AOF/复制缩小窗口，最近受理写仍可能丢失 | 设计边界，需量化 RPO |

完成状态表示代码路径存在，不等于故障演练已经通过。验证必须保存命令、数据状态和恢复时间。
