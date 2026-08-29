# ADR 002：先持久化 Intent，再预占座位

- 状态：已废弃；当前决策见 `../ADR-002-redis-stream-order-event.md`

这是早期“先持久化 Intent”设计记录，仅保留用于架构取舍复盘。当前 v5 已删除创建订单 Intent/Outbox 代码和物理表，改为锁座与 `XADD` 同一 Lua。
