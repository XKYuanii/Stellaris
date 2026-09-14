package com.stellaris.domain;

import com.stellaris.dto.OrderTicketUserCreateDto;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: Redis Stream 订单创建事件
 * @author: xz_y
 **/
@Data
public class OrderCreateEvent {

    /** 客户端幂等号；v5 Redis 锁座重放以它派生稳定预订标识。 */
    private String requestId;

    /** Program 生成的稳定请求摘要，订单库据此识别同请求换参数。 */
    private String requestFingerprint;

    /** 服务端发布的账号限购上限，Redis 预检和 MySQL 最终裁决使用同一快照。 */
    private Integer accountLimit;

    /** 开售库存版本，防止旧 Stream 消息写入新一轮销售库存。 */
    private String saleVersion;

    /** 可靠事件唯一标识，用于生产记录、消费幂等和故障追踪。 */
    private Long eventId;

    /** Redis reservation owner and end-to-end idempotency identity. */
    private String intentId;

    /** v5 服务端座位快照，随 Stream 事件传播，供故障定位和对账使用。 */
    private String seatSnapshot;

    /** Redis 预占失效时间；不是订单支付超时的最终裁决。 */
    private Date reservationExpireTime;
    
    private Long orderNumber;
 
    private Long programId;
   
    private String programItemPicture;
    
    private Long userId;
    
    private String programTitle;
    
    private String programPlace;
    
    private Date programShowTime;
    
    private Integer programPermitChooseSeat;
    
    private String distributionMode;
    
    private String takeTicketMode;
    
    private BigDecimal orderPrice;
    
    private Date createOrderTime;
    
    private List<OrderTicketUserCreateDto> orderTicketUserCreateDtoList;
    
}
