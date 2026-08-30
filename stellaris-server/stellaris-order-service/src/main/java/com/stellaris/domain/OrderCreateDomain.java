package com.stellaris.domain;

import com.stellaris.dto.OrderTicketUserCreateDto;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 订单创建 需要的数据
 * @author: xz_y
 **/
@Data
public class OrderCreateDomain {
    
    private Long identifierId;

    private String intentId;
    
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
    
    private Integer orderVersion;
    
}
