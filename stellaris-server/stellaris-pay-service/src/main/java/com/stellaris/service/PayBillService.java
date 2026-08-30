package com.stellaris.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.stellaris.entity.PayBill;
import com.stellaris.mapper.PayBillMapper;
import org.springframework.stereotype.Service;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 支付账单 service
 * @author: xz_y
 **/
@Service
public class PayBillService extends ServiceImpl<PayBillMapper, PayBill> {

}
