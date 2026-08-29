# ADR 001：Lua 只提交锁座，不承担全场选座计算

- 状态：已采用（v5/reference 基础设施）
- 日期：2026-08-25

## 决策

自动选座先从 `seat:available:{ticketCategory}` ZSET 读取固定上限候选，在 Java 中计算连座组合；手动或自动候选最终均由短 Lua 脚本提交。

脚本只处理本次座位数 `k`：检查重复 seatId、检查 ZSET 可售状态、检查 meta 中票档和价格、`ZREM`、写 `owner`、`reservation` 与首次 `result`。复杂度为 O(k)，不允许 `HVALS`、全场 JSON 解析或排序。

## 后果

- 相比节目级 Redisson 锁，热点请求不会串行化整个选座计算。
- 相比旧 v31 的全量 Lua，避免 Redis 主线程因场馆规模放大尾延迟。
- 两步 CAS 可能冲突，自动选座调用方最多重试 2～3 次；基准测试将决定具体候选窗口。
