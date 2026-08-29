package com.stellaris.service.lua;

import com.stellaris.domain.PurchaseSeat;
import lombok.Data;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目缓存更新 实体
 * @author: 阿星不是程序员
 **/
@Data
public class ProgramCacheCreateOrderData {

    private Integer code;
    
    private List<PurchaseSeat> purchaseSeatList;
}
