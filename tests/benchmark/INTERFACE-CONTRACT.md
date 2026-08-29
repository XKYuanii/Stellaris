# 压测接口契约

本文只记录已从 Controller、DTO、Gateway 路由和现有已验证脚本中确认的字段。任何换环境后可能被覆盖的值都必须重新核对；未确认项写 `【待项目提供】`。

## 通用响应

所有 JSON 业务接口使用：

```json
{
  "code": 0,
  "message": null,
  "data": "接口数据"
}
```

`code=0` 才是业务成功。HTTP 2xx 但 `code!=0` 仍算失败；JMX 已按此规则标记样本。雪花 ID 在请求模板和结果留档中均按字符串处理，禁止经 JavaScript Number、Lua `cjson` Number 或 Excel 科学计数法转换。

## 1. V5 自动配座创建订单

- 接口名称：V5 自动配座
- 作用：执行限流、自动候选、Lua 锁座、XADD，返回本次幂等业务的订单号；MySQL 订单随后由 Stream Relay → Kafka → Consumer 异步创建。
- HTTP Method：`POST`
- 正式 Gateway URL：`http://127.0.0.1:6085/stellaris/program/program/order/create/v5`
- 定位用直连 URL：`http://127.0.0.1:6086/program/order/create/v5`
- Headers：`Content-Type: application/json;charset=UTF-8`；项目真实 token Header 为 `token: ${token}`。
- 本地压测旁路：`no_verify: true` 可跳过 Gateway 签名/token 检查；它不能用于生产，且仍经过 Gateway 自研限流/舱壁。
- Path 参数：无。
- Query 参数：无。
- 是否参与正式压测：是，唯一主采样器。

自动配座 Request Body：

```json
{
  "requestId": "每次独立请求的新 UUID",
  "programId": "900000",
  "userId": "${userId}",
  "ticketUserIdList": ["${ticketUserId}"],
  "ticketCategoryId": "900001",
  "ticketCount": 1
}
```

`seatDtoList` 在自动配座请求中省略。`ticketCount` 必须等于 `ticketUserIdList` 数量，当前基线固定为 1，避免“请求 QPS”和“座位消费 TPS”混淆。

成功 Response Body 示例结构：

```json
{
  "code": 0,
  "message": null,
  "data": "<orderNumber>"
}
```

- 成功条件：HTTP 2xx、可解析 JSON、`code=0`、`data` 是非空纯数字订单号字符串。
- 失败条件：连接/超时/非 2xx；响应不可解析；`code!=0`；订单号为空或非数字。
- 需要提取的字段：`data` → `orderNumber`，只用于追溯/可选端到端探针，不能转成浮点数。
- `requestId`：JMX 每次循环调用 `UUID.randomUUID()`；绝不跨独立请求复用。
- `intentId/orderNumber/eventId`：当前实现均由服务端在一次 create 内生成。`intentId=SHA-256(programId:userId:requestId)`；候选重试由服务端完成，重试时保持同一组三个标识。JMeter 不应自行重试业务请求；网络层重试也默认关闭，以免改变幂等流量模型。

## 2. Program 数据预热

- 接口名称：V5 专用节目 Redis 快照预热
- 作用：从 MySQL 构建节目座位 meta、available ZSET、ready/version 等 V5 Redis 状态。
- Method：`POST`
- URL：`http://127.0.0.1:6086/program/data/preheat`
- Headers：`Content-Type: application/json;charset=UTF-8`、`no_verify: true`
- Path/Query 参数：无。
- Request Body：

```json
{ "programId": "900000" }
```

- Response Body：通用响应，`data` 的具体类型不作为脚本依赖。
- 成功条件：HTTP 2xx 且 `code=0`，随后 `ready=1`、available ZCARD=10000。
- 失败条件：任何其他情况或后置 Redis 校验失败。
- 需要提取字段：无。
- 是否参与正式压测：否；只在 prepare/reset 后调用。把冷启动混入正式样本会污染容量结论。

## 3. 订单缓存查询

- 接口名称：查看缓存中的订单
- 作用：端到端探针确认异步订单已可查询。
- Method：`POST`
- 直连 URL：`http://127.0.0.1:8081/order/get/cache`
- Gateway URL：`http://127.0.0.1:6085/stellaris/order/order/get/cache`
- Headers：`Content-Type: application/json;charset=UTF-8`；Gateway 正常鉴权时使用 `token`，本地直连/旁路使用 `no_verify: true`。
- Path/Query 参数：无。
- Request Body：

```json
{ "orderNumber": "${orderNumber}" }
```

- Response Body：`ApiResponse<String>`；缓存未就绪时 `data` 的精确空值表现 `【待运行环境确认】`。
- 成功条件：HTTP 2xx、`code=0` 且 `data` 非空。
- 失败条件：`code!=0`、超时，或在端到端等待上限内始终为空。
- 需要提取字段：`data`。
- 是否参与正式压测：默认否。若开启 E2E 探针，必须以低频独立探针运行并单独报告，不能把查询 TPS 混入 create QPS。

## 4. 订单详情查询

- 接口名称：查看订单详情
- 作用：确认 MySQL 订单终态、订单状态与座位信息。
- Method：`POST`
- 直连 URL：`http://127.0.0.1:8081/order/get`
- Gateway URL：`http://127.0.0.1:6085/stellaris/order/order/get`
- Headers/Body：与缓存查询相同。
- Response Body：`ApiResponse<OrderGetVo>`；已确认关键字段包括 `orderNumber/programId/userId/orderStatus/orderTicketInfoVoList`。订单状态：1 未支付、2 已取消、3 已支付、4 已退单。
- 成功条件：HTTP 2xx、`code=0`、`data.orderNumber` 与请求一致。
- 失败条件：其他情况。
- 需要提取字段：按校验需要提取 `orderStatus` 和座位列表。
- 是否参与正式压测：否；只用于抽样正确性和恢复验证。

## 5. 真实取消订单

- 接口名称：订单详情取消
- 作用：进入真实取消/库存迁移链路，验证订单取消、座位释放、owner/reservation 删除和库存恢复。
- Method：`POST`
- 正常恢复直连 URL：`http://127.0.0.1:8081/order/cancel`
- Gateway URL：`http://127.0.0.1:6085/stellaris/order/order/cancel`
- Headers：`Content-Type: application/json;charset=UTF-8`、本地脚本使用 `no_verify: true`。
- Path/Query 参数：无。
- Request Body：

```json
{ "orderNumber": "<orderNumber>" }
```

- Response Body：`ApiResponse<Boolean>`。
- 成功条件：HTTP 2xx 且 `code=0`；`data` 的 Boolean 值不是最终恢复判定，必须等待异步链路并核对 MySQL/Redis。
- 失败条件：调用异常、`code!=0`，或最终状态不收敛。
- 需要提取字段：`code/data` 仅用于日志。
- 是否参与正式压测：否；每轮结束后的正常恢复使用。边压边取消会增加额外负载，不能与纯购票容量结果混用。

## 6. 用户登录（Token 方案 B 模板）

- 接口名称：用户登录
- 作用：获取 `userId/token`。
- Method：`POST`
- 直连 URL：`http://127.0.0.1:6082/user/login`
- Gateway URL：`http://127.0.0.1:6085/stellaris/user/user/login`
- Headers：`Content-Type: application/json;charset=UTF-8`；Gateway 的完整签名包装字段 `【待项目提供】`，本地可用 `no_verify:true`。
- Request Body：

```json
{
  "code": "0001",
  "mobile": "<mobile 或省略>",
  "email": "<email 或省略>",
  "password": "<password>"
}
```

- Response Body：`ApiResponse<UserLoginVo>`，`data.userId`、`data.token`。
- 成功条件：HTTP 2xx、`code=0`、两字段非空。
- 失败条件：其他情况。
- 需要提取字段：`$.data.userId`、`$.data.token`。
- 是否参与正式压测：否，只能放在 Setup Thread Group 或测试前独立准备。
- 当前限制：专用 1000 用户的密码字段为 NULL，所以现有 fixture 无法用此方案登录；必须先由项目提供合法账号准备方式。

# Token 两种方案

## 方案 A：测试前准备 `users.csv`（推荐）

CSV：

```csv
userId,ticketUserId,token
910000000000000000,920000000000000000,<token或本地no_verify时留空>
```

JMeter 的 CSV Data Set Config 使用全局共享、循环读取；这样 1000 个用户轮换，而不是所有线程使用同一账号。优点是登录不计入购票资源、流量和延迟，结果稳定且可复现；缺点是 token 有过期时间，执行前必须抽样校验。

## 方案 B：Setup Thread Group 登录

结构为 `CSV(登录凭据) → POST /user/login → JSON Extractor(userId/token) → 写入运行时数据`。优点是 token 新鲜；缺点是需要并发安全地把每个账号 token 传给主线程组，登录会延长准备时间，失败会造成用户池缺口，而且当前 fixture 无有效密码。仅用于 token 自动续期验证，不推荐用于容量基线。
