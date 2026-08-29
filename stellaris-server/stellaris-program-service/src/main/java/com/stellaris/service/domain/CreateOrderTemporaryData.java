package com.stellaris.service.domain;

import com.stellaris.domain.PurchaseSeat;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 创建订单临时需要的数据
 * @author: 阿星不是程序员
 **/
@Data
@AllArgsConstructor
public class CreateOrderTemporaryData {

    /**
     * 记录id
     */
    private Long identifierId;
    
    /**
     * 购买的座位
     * */
    private List<PurchaseSeat> purchaseSeatList;
   
}
