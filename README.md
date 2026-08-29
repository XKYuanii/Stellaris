# Stellaris（星演）票务交易工程化演示系统

Stellaris（星演）是一个面向 Java 面试与架构学习的票务交易原型，重点展示热门演出场景下的限流、原子锁座、可靠事件、业务幂等、状态机和对账恢复。

项目定位是“可运行、可解释、可验证的工程化演示系统”，不是生产部署模板，也不宣称端到端 Exactly Once、跨存储强事务或未经实测的 QPS。工程是在既有微服务学习骨架上完成交易可靠性与高并发治理重构；简历中的个人贡献应按实际改造范围描述。

## 当前参考主链

`v5/reference` 是唯一建议用于演示和面试说明的下单链路，`v1`～`v4.1` 只保留为架构演进对照。

```text
Gateway 用户/节目/全局 Redis 令牌桶
  -> 本机并发舱壁
  -> 节目状态、开售时间、单笔校验（缓存）
  -> 有界 O(k) Redis Lua：账号限购 + 精确锁座 + XADD Stream
  -> Redis Stream Consumer Group/Pending 重领/死信
  -> Kafka acks=all / 幂等生产 / 消费端手动提交 / 重试 / DLT
  -> MySQL 座位 reservationId CAS + 订单和购票人明细幂等落库
  -> 支付或取消使用 MySQL CAS 决定唯一终态
  -> 本地迁移事件可靠确认 SOLD 或释放座位
  -> 延迟取消 lease/ACK 队列 + 数据库过期扫描兜底
  -> Stream / Order / Pay / Redis 对账
```

可靠性语义是“至少一次投递 + 业务幂等 + 状态 CAS + 最终一致性”。v5 不写 MySQL Intent/Outbox；锁座变化和 Stream 事件由同一 Lua 原子生成。这个选择缩短热路径，但把创建事件的耐久性边界放在 Redis 主从/AOF 上，不能宣称跨存储强一致或绝对零丢失。

## 可演示能力

- 订单号采用互不重叠的时间、节点、序列和 6 位路由基因；Snowflake 节点使用 Redis 租约，失去租约后本地拒绝继续发号。
- 自定义 ShardingSphere 复杂分片支持多值 `IN` 跨库跨表路由，并校验 2 的幂和 6 位基因容量。
- v5 按 16 个 `{sale:shard}` Hash Tag 分散节目；同一节目库存键和 Stream 始终同槽，Lua 不发生 CROSSSLOT。
- 网关按可信 USER、PROGRAM、GLOBAL 三层令牌桶限流，限流通过后才占用下游并发舱壁。
- 创建事件使用 Redis Stream；Kafka 生产、消费、重试和 DLT 均有明确状态与指标。
- reservationId 对应带 TTL 的 Redis 幂等回执；自动选座请求重试不会因为第一次座位已移出 ZSET 而改选或生成新订单号。
- 支付/取消使用 `NO_PAY -> PAY/CANCEL` 条件更新；只有 CAS 赢家可以产生座位迁移事件。
- 模拟支付支持前端直接选择“成功”或“失败”。成功会走真实订单与座位终态链路，失败保持订单未支付。
- 退款明确只支持全额退款，使用稳定退款号、Intent、最大重试次数、退避和 DEAD 状态。
- 对账检查 Stream/死信、订单事实和 owner/reservation；到期清理只有在事件尚未交给 Kafka、订单不存在且 MySQL 未锁座时才释放。

## 本地验证

环境要求：JDK 17、Maven、Node.js、Docker Desktop。

```powershell
# 基础设施（需要时启动）
docker compose -p stellaris-interview -f ops/docker-compose.interview.yml up -d

# 后端全量单元测试
mvn test

# 前端构建
Set-Location vue3
npm install
npm run build

# 前端开发服务器
npm run dev
```

已有数据库需要按 [可靠性 SQL 清单](sql/reliability/README.md) 执行升级脚本；全新 Docker 数据卷由 Stellaris 初始化 SQL 建表。不要在有业务数据的环境直接执行演示重置。

## 本地密钥配置

仓库不保存 RSA 私钥或支付内容密钥。前端请从 `vue3/.env.example` 创建本地 `.env.development` / `.env.production`；启用请求签名前，需要同时配置 `VITE_SIGN_SECRET_KEY` 和数据库渠道密钥。支付宝集成从环境变量 `ALIPAY_MERCHANT_PRIVATE_KEY`、`ALIPAY_CONTENT_KEY` 读取敏感值。Java RSA 演示入口使用 `STELLARIS_RSA_*`、`STELLARIS_DEMO_*` 环境变量。

## 文档入口

- [架构演进](docs/ARCHITECTURE_EVOLUTION.md)
- [业务不变量](docs/BUSINESS_INVARIANTS.md)
- [已知边界](docs/KNOWN_BOUNDARIES.md)
- [故障矩阵](docs/FAILURE_MATRIX.md)
- [本轮修复核销报告](docs/audit/20260826_REMEDIATION_RESULT.md)
- [简历项目介绍](docs/RESUME_PROJECT_STELLARIS_2026.md)
- [带来源的面试题库](docs/INTERVIEW_BANK_SOURCED_2026.md)
- [修改清单](CHANGELOG.md)

## 必须主动说明的边界

- 本地 Docker 是单节点 Kafka、Redis、MySQL、Nacos 和 Elasticsearch，只能证明流程可执行，不能证明基础设施高可用。
- Redis 预订到期后，订单服务会拒绝过期事件；宽限期后仅在订单事实仍不存在时释放，避免死信长期占座。
- 当前对账结果通过接口、日志和 Prometheus 暴露，尚未落 `reconciliation_run/finding` 历史表。
- 遗留版本 Lua 仅供对照，不能整体宣称支持 Redis Cluster；当前承诺范围只包括 v5、延迟取消和令牌桶主链。
- 本轮只执行编译、单元测试和前端构建。高并发抢座、`kill -9`、中间件中断和恢复收敛证据属于下一阶段。
