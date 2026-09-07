# 已知边界

本项目当前的目标是说明高并发下单链路的设计与演进，不把它包装成完整的生产安全体系。2026-08-26 代码修复核销结果见 `audit/20260826_REMEDIATION_RESULT.md`。

- Gateway 默认要求签名访问，并统一生成下游 `userId`；完整后台权限、密钥轮换和风控仍须由专门的安全方案覆盖。
- Token 是当前唯一的身份边界，RSA 请求签名只用于完整性校验，不能替代认证。上游遗留的 `checkTokenPaths` 仍是“命中才鉴权”的反向清单，`BackManageAuthFilter` 也依赖客户端主动携带 `back_manage`；因此未覆盖的管理接口不构成完整后台授权体系，必须限制在可信管理网络，后续应迁移到默认鉴权、显式匿名白名单和 RBAC。
- 单节点本地 Kafka/Redis/MySQL 演示环境不等于高可用生产环境；生产参数和故障演练另行记录。
- `v1`～`v4.1` 只保留在历史测试材料和架构演进记录中；当前代码只以 `v5/reference` 作为下单链路。
- Redis 与 MySQL 不构成跨存储强事务。v5 让锁座与 `XADD` 在同一 Lua 中原子发生，不使用 MySQL Intent/Outbox；可靠性依赖 Redis Stream、主从/AOF、Kafka 至少一次、MySQL CAS 和对账，不宣称绝对零丢失。
- 节目库 LOCK 到订单库提交之间仍是 Saga：永久建单失败时先保留事实、隔离重放并双查订单，当前不做可能误放已成交座位的自动补偿。
- 对账当前提供运行日志、接口结果和 Prometheus 指标，尚未持久化 `reconciliation_run/finding` 历史。
- 普通预热会保留 SOLD 并在异常时 fail-closed，但仍是在维护闸门内原地重建，不是双版本无感切换。
- Stream 中继固定枚举 16 个销售分片；到期任务和对账仍会枚举节目，演示规模可接受，生产规模需引入调度分片。
- Java 包名和 Maven 模块目录保留既有兼容标识；运行库、容器、Topic、Redis Key、服务名和用户可见品牌使用 `stellaris/星演`。
- 运维重放、缓存查询和对账入口默认关闭，外部 Gateway 固定返回 404；启用后仍应只部署在受控管理网络。
- Program/Order/User 等业务服务端口必须位于可信内网。直接暴露业务服务会绕过 Gateway 的验签、可信身份和外部接口隔离。
- 历史 V4/V5 性能材料直连单实例 Program Service 并绕过 Gateway，只能用于比较同步接单热路径；当前未形成新一轮 Gateway 端到端容量、进程硬终止和中间件故障注入报告。
