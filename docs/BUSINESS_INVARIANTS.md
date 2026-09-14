# 业务不变量

以下不变量是当前 v5/reference 的验收基准。

1. 同一用户、场次和 requestId 只能形成一个 reservationId、一个 orderNumber 和一个最终结果；相同请求号换业务参数必须拒绝。
2. 一个座位在交易库中只能处于 AVAILABLE、被一个 reservation/order 绑定的 LOCKED、或 SOLD。
3. 一笔多座位订单必须全部锁定或全部回滚，不允许部分成单。
4. 同一场次的 AVAILABLE + LOCKED + SOLD 座位数等于已发布销售座位总数。
5. t_seat_inventory 是销售状态权威；Redis 是前置准入和展示模型，短暂差异不能反向覆盖数据库事实。
6. 价格、票档和销售版本来自服务端发布快照，不信任客户端金额。
7. t_account_program_purchase 记录有效锁定与已售票数；创建成功增加，未支付取消只由状态赢家减少，支付不改变。
8. 订单只允许从 NO_PAY 进入一个终态；支付和取消不能同时成功。
9. Stream 可以重复投递；任何重复、重领和人工重放都不能产生第二笔订单、重复额度变化或重复座位状态变化。
10. 只有形成持久化 CREATED、REJECTED 或异常审计证据后才能 ACK。未知数据库提交结果必须按原请求重查或重试。
11. Redis 终态同步必须验证 reservation/order 归属；迟到释放不能改变后来预订。

HTTP 受理成功不等于订单创建成功。客户端必须继续查询 t_order_request 对应的物化结果。
