# 压测测试数据说明

本文件描述专用 fixture 的业务关系和物理存储。维护者不需要先读源码，但在修改任何 ID、分片或 Key 前必须同步修改 SQL、PowerShell、JMX 和本文件。

> **仅限独立本地测试环境。禁止将这些清理规则套用到生产节目。**

## 业务数据总表

| 项目 | 值 | 关系/用途 |
|---|---:|---|
| 测试节目 ID | `900000` | 所有座位、票档、订单、Redis Key 的业务隔离主键 |
| 节目名称 | `V5并发压测专用节目` | 只供 benchmark profile |
| 节目地区 | `area_id=2`，北京 | 初始化数据 |
| 票档 ID | `900001` | 自动配座只从此票档 available ZSET 选座 |
| 票档名称/价格 | `压测票档A` / 199 | 价格单位沿用项目表定义 |
| 座位总数 | `10000` | 100 行 × 100 列，座位 ID 从 `930000000000000000` 起 |
| 座位区域 | 逻辑上为单一热门票档；row 1..100、col 1..100 | 项目当前 fixture 无独立“区域 ID”字段，不虚构区域字段 |
| 每请求票数 | `1` | 基线固定；所以请求数=预期消费座位数 |
| 每单/每账号限购 | `6 / 6` | 1000 用户使每轮最多成功 6000 次，是比 10000 座更早的预算上限 |
| 测试用户 | `910000000000000000..910000000000000999` | 1000 个账号，CSV 全局轮换 |
| 观演人 | `920000000000000000..920000000000000999` | 与用户按相同 offset 一对一 |
| Token | `【待项目提供】` | 当前 fixture 用户密码为 NULL；`no_verify:true` 时可留空 |

## 数据关系

```text
program 900000
└── ticket category 900001
    └── 10,000 seats (seat_base + 0..9999)

user (user_base + n)
└── ticket user (ticket_user_base + n)

一次 JMeter 循环
├── 从共享 CSV 取 user/ticketUser/token
├── 新建 requestId
├── 对 category 900001 自动选 1 座
└── 服务端生成 intentId/orderNumber/eventId
```

所有大整数都可能超过 `2^53`。CSV 不经 Excel 保存；JMX Groovy 将 ID 作为字符串写 JSON，Spring/Jackson 可反序列化为 Long。Lua 事件 payload 中的雪花 ID 也必须是 JSON String。

## MySQL 物理表

初始化已确认使用：

- `stellaris_program_0.d_program_group_0`
- `stellaris_program_0.d_program_0`
- `stellaris_program_0.d_program_show_time_0`
- `stellaris_program_0.d_ticket_category_0`
- `stellaris_program_0.d_seat_0`
- `stellaris_user_0/1.d_user_0/1`
- `stellaris_user_0/1.d_ticket_user_0/1`

下单/恢复检查会遍历：

- `stellaris_order_0/1.d_order_0..3`
- `stellaris_order_0/1.d_order_ticket_user_0..3`
- `stellaris_order_0/1.d_order_program_0..1`
- 含 `program_id` 的 `d_reservation_transition_event*`、`d_order_create_event*`、`d_order_create_dlt_record*`（实际存在情况由 information_schema 获取）
- `stellaris_program_0/1.d_order_inventory_operation_0/1`

订单状态已确认：1 未支付、2 已取消、3 已支付、4 已退单。正常恢复要求本轮订单进入 2；任何状态 3 会触发脚本拒绝硬恢复。

## Redis V5 Key

`900000 % 16 = 0`，同节目 Key 共享 Hash Tag `{sale:0}`：

| 类型 | Key | 初始值/用途 |
|---|---|---|
| HASH | `stellaris:{sale:0}:program:900000:seat:meta` | 10000 个座位元数据 |
| ZSET | `stellaris:{sale:0}:program:900000:seat:available:900001` | 初始 ZCARD=10000 |
| HASH | `stellaris:{sale:0}:program:900000:seat:owner` | 初始 HLEN=0，seat→intent |
| HASH | `stellaris:{sale:0}:program:900000:seat:reservation` | 初始 HLEN=0，intent→预留状态 |
| HASH | `stellaris:{sale:0}:program:900000:seat:account-count` | 每账号已占票数；重置后为空/不存在 |
| HASH | `stellaris:{sale:0}:program:900000:seat:reservation:result` | Lua 结果 |
| SET | `stellaris:{sale:0}:program:900000:seat:sold` | 已售座位 |
| HASH | `stellaris:{sale:0}:program:900000:seat:reservation:final` | 预留终态 |
| ZSET | `stellaris:{sale:0}:program:900000:seat:reservation:expiration` | 到期索引 |
| STRING | `stellaris:{sale:0}:program:900000:seat:reservation:receipt:<intentId>` | 24h 幂等回执 |
| STRING | `stellaris:{sale:0}:program:900000:seat:ready` | 预热就绪标志，必须存在 |
| STRING | `stellaris:{sale:0}:program:900000:seat:maintenance` | 预热互斥 |
| STRING | `stellaris:{sale:0}:program:900000:seat:version` | 快照版本 |

初始化/恢复还会清理该节目对应的旧版 `*` 缓存以及 1000 用户的票夹/限购缓存，具体清单由 `StellarisV5Benchmark.Common.ps1` 维护。

## Redis Stream

- 主 Stream：`stellaris:{sale:0}:reservation:event:stream`
- Dead Stream：`stellaris:{sale:0}:reservation:event:dead-stream`
- Consumer Group：`stellaris-order-relay`
- 主消息字段：`intentId`、`payload`
- 正常 Relay：Kafka ACK 成功后先 XACK，再 XDEL，所以稳定状态 `XLEN=0`、`XPENDING=0`。

Stream 是 sale shard 级，不是 program 级；同为 shard 0 的其他节目会共享它。因此强制恢复不得无条件 `DEL` 整个 Stream。当前门禁要求总长度为 0，若有其他节目消息，必须先隔离测试环境或等待其正常消费，不能把别的节目的消息当测试垃圾删除。

## Kafka

- Broker：`127.0.0.1:9092`
- Docker 容器：`stellaris-interview-kafka-1`
- Topic：`${prefix.distinction.name}-${spring.kafka.topic}`，当前默认 `stellaris-create_order`
- 分区数：3
- Consumer Group：`create_order_data`
- DLT：`stellaris-create_order.DLT`，审计 group 默认 `${spring.application.name}-dlt-audit`

每轮记录 `CURRENT-OFFSET/LOG-END-OFFSET/LAG`。验收不是“某个时刻看见过 LAG”，而是压力停止后 LAG 能否归零；持续增长说明生产速度大于订单消费/建单能力。

## `users.csv`

最终 CSV 列固定为：

```csv
userId,ticketUserId,token
```

`prepare-benchmark.ps1` 从已验证 fixture 重新生成 1000 行。CSV Data Set Config 使用 `Sharing mode=All threads`、`Recycle on EOF=true`、`Stop thread on EOF=false`，确保全局轮换。账号会重复使用，但每个独立请求的 requestId 永不复用。
