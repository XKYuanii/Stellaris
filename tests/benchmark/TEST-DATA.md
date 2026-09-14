# V5 隔离测试数据

准备脚本只允许操作以下保留范围：

| 对象 | 范围 |
| --- | --- |
| Program | 900000 |
| Ticket category | 900001 |
| Seats | 930000000000000000 起，共 10000 |
| Users | 910000000000000000 起，共 5000 |
| Ticket users | 920000000000000000 起，共 5000 |

Program/User 的静态种子仍写入既有分片表。销售状态、账号额度、请求结果和订单只写入 stellaris_trade：

- t_seat_inventory
- t_account_program_purchase
- t_order_request
- d_order
- d_order_ticket_user
- d_order_program
- d_reservation_transition_event
- d_order_stream_failure

旧 d_ticket_category.remain_number 和 d_seat.sell_status 只为兼容历史初始化表结构而填值，当前运行代码不读取或更新它们。

Redis 使用：

- stellaris:{sale:0}:program:900000:seat:meta
- stellaris:{sale:0}:program:900000:seat:available:900001
- owner、reservation、result、sold、final-state、account-count、ready、version
- stellaris:{sale:0}:reservation:event:stream
- Consumer Group stellaris-order-service

准备和清理脚本不删除共享 Stream，因为同一销售分片可能还有其他节目。它们要求消费完成后 Stream/PEL 自然收敛，只清理 program 900000 的专属 Redis Key。
