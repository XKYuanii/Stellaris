# V5 基准接口契约

## 创建订单

Gateway：

    POST http://127.0.0.1:6085/stellaris/program/program/order/create/v5

隔离本机无签名模式需要 no_verify: true 和 X-Stellaris-Demo-User-Id。生产模式必须使用正常签名和 Token。

请求字段：

| 字段 | 约束 |
| --- | --- |
| requestId | 同一业务重试必须稳定 |
| programId | 测试固定 900000 |
| userId | 必须与 Gateway 身份一致 |
| ticketUserIdList | 与票数一致，属于当前用户 |
| ticketCategoryId | 自动选座票档 |
| ticketCount | 1～6 |
| seatDtoList | 手工选座时提供，服务端重新读取价格和票档 |

返回 code=0 和 orderNumber 只表示 Redis 已受理。测试必须继续查询订单或物化结果。

## 订单查询

    POST /stellaris/order/order/get

订单未落地会返回 ORDER_NOT_EXIST，不能立刻解释为创建失败。

## 物化结果

    POST /stellaris/order/order/materialization

返回 PROCESSING、CREATED 或 REJECTED。该接口按 Gateway 当前用户过滤，不允许跨用户探测。

## 取消

    POST /stellaris/order/order/cancel

成功后还要等待 Redis 同步记录完成，再断言 available/owner/reservation。交易库订单状态、座位和账号额度在取消事务提交时已经是权威事实。
