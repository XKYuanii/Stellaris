package com.stellaris.service;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson.JSON;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellaris.dto.NotifyDto;
import com.stellaris.dto.PayBillDto;
import com.stellaris.dto.PayDto;
import com.stellaris.dto.RefundDto;
import com.stellaris.dto.TradeCheckDto;
import com.stellaris.entity.PayBill;
import com.stellaris.entity.RefundBill;
import com.stellaris.entity.RefundIntent;
import com.stellaris.enums.BaseCode;
import com.stellaris.enums.PayBillStatus;
import com.stellaris.enums.PayChannel;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.mapper.PayBillMapper;
import com.stellaris.mapper.RefundBillMapper;
import com.stellaris.mapper.RefundIntentMapper;
import com.stellaris.pay.PayResult;
import com.stellaris.pay.PayStrategyContext;
import com.stellaris.pay.PayStrategyHandler;
import com.stellaris.pay.RefundResult;
import com.stellaris.pay.TradeResult;
import com.stellaris.servicelock.annotion.ServiceLock;
import com.stellaris.util.DateUtils;
import com.stellaris.vo.NotifyVo;
import com.stellaris.vo.PayBillVo;
import com.stellaris.vo.PayResultVo;
import com.stellaris.vo.TradeCheckVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;
import java.util.Objects;

import static com.stellaris.constant.Constant.ALIPAY_NOTIFY_FAILURE_RESULT;
import static com.stellaris.constant.Constant.ALIPAY_NOTIFY_SUCCESS_RESULT;
import static com.stellaris.core.DistributedLockConstants.COMMON_PAY;
import static com.stellaris.core.DistributedLockConstants.TRADE_CHECK;
import static com.stellaris.core.DistributedLockConstants.REFUND_PAYMENT;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 支付 service
 * @author: xz_y
 **/
@Slf4j
@Service
public class PayService {
    
    @Autowired
    private PayBillMapper payBillMapper;
    
    @Autowired
    private RefundBillMapper refundBillMapper;
    @Autowired
    private RefundIntentMapper refundIntentMapper;
    
    @Autowired
    private PayStrategyContext payStrategyContext;
    
    @Autowired
    private UidGenerator uidGenerator;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Value("${refund-intent-retry.max-attempts:10}")
    private int refundMaxAttempts;

    @Value("${refund-intent-retry.backoff-ms:60000}")
    private long refundRetryBackoffMs;
    
    /**
     * 通用支付，用订单号加锁防止多次支付成功，不依赖第三方支付的幂等性
     * */
    @ServiceLock(name = COMMON_PAY,keys = {"#payDto.orderNumber"})
    public PayResultVo commonPay(PayDto payDto) {
        PaymentPreparation preparation = transactionTemplate.execute(status -> preparePayment(payDto));
        if (preparation == null) {
            throw new IllegalStateException("payment preparation returned null");
        }
        if (preparation.alreadyPaid()) {
            return new PayResultVo(payDto.getChannel(), "PAID", null, "支付结果已存在");
        }
        PayStrategyHandler payStrategyHandler = payStrategyContext.get(payDto.getChannel());
        // 渠道调用位于数据库事务之外，避免网络等待长期占用连接和行锁。
        PayResult pay = payStrategyHandler.pay(payDto);
        if (!pay.isSuccess()) {
            return new PayResultVo(payDto.getChannel(), "FAILED", null, pay.getMessage());
        }
        if ("PAID".equals(pay.getState())) {
            transactionTemplate.executeWithoutResult(status -> confirmPayment(preparation.payBill()));
        }
        return new PayResultVo(payDto.getChannel(), pay.getState(), pay.getBody(), pay.getMessage());
    }

    private PaymentPreparation preparePayment(PayDto dto) {
        PayBill payBill = payBillMapper.selectOne(Wrappers.lambdaQuery(PayBill.class)
                .eq(PayBill::getOutOrderNo, dto.getOrderNumber()));
        if (payBill != null) {
            if (payBill.getPayAmount().compareTo(dto.getPrice()) != 0
                    || !Objects.equals(payBill.getPayChannel(), dto.getChannel())) {
                throw new StellarisFrameException("支付金额或渠道与已有账单不一致");
            }
            if (Objects.equals(payBill.getPayBillStatus(), PayBillStatus.PAY.getCode())
                    && Objects.equals(dto.getChannel(), PayChannel.MOCK.getValue())) {
                return new PaymentPreparation(payBill, true);
            }
            if (!Objects.equals(payBill.getPayBillStatus(), PayBillStatus.NO_PAY.getCode())) {
                throw new StellarisFrameException(BaseCode.PAY_BILL_IS_NOT_NO_PAY);
            }
            return new PaymentPreparation(payBill, false);
        }
        Date now = DateUtils.now();
        payBill = new PayBill();
        payBill.setId(uidGenerator.getUid());
        payBill.setOutOrderNo(dto.getOrderNumber());
        payBill.setPayChannel(dto.getChannel());
        payBill.setPayScene(Objects.equals(dto.getChannel(), PayChannel.MOCK.getValue()) ? "模拟测试" : "生产");
        payBill.setSubject(dto.getSubject());
        payBill.setPayAmount(dto.getPrice());
        payBill.setPayBillType(dto.getPayBillType());
        payBill.setPayBillStatus(PayBillStatus.NO_PAY.getCode());
        payBill.setPayTime(null);
        payBill.setCreateTime(now);
        payBill.setEditTime(now);
        payBill.setStatus(1);
        payBillMapper.insert(payBill);
        return new PaymentPreparation(payBill, false);
    }

    private void confirmPayment(PayBill payBill) {
        PayBill update = new PayBill();
        update.setPayBillStatus(PayBillStatus.PAY.getCode());
        update.setPayTime(DateUtils.now());
        update.setEditTime(DateUtils.now());
        int updated = payBillMapper.update(update, Wrappers.lambdaUpdate(PayBill.class)
                .eq(PayBill::getId, payBill.getId())
                .eq(PayBill::getPayBillStatus, PayBillStatus.NO_PAY.getCode()));
        if (updated == 0) {
            PayBill current = payBillMapper.selectById(payBill.getId());
            if (current == null || !Objects.equals(current.getPayBillStatus(), PayBillStatus.PAY.getCode())) {
                throw new IllegalStateException("payment confirmation CAS failed");
            }
        }
    }

    private record PaymentPreparation(PayBill payBill, boolean alreadyPaid) {}
    
    @Transactional(rollbackFor = Exception.class)
    public NotifyVo notify(NotifyDto notifyDto){
        NotifyVo notifyVo = new NotifyVo();
        log.info("回调通知参数 ===> {}", JSON.toJSONString(notifyDto));
        Map<String, String> params = notifyDto.getParams();
   
        PayStrategyHandler payStrategyHandler = payStrategyContext.get(notifyDto.getChannel());
        boolean signVerifyResult = payStrategyHandler.signVerify(params);
        if (!signVerifyResult) {
            notifyVo.setPayResult(ALIPAY_NOTIFY_FAILURE_RESULT);
            return notifyVo;
        }
        LambdaQueryWrapper<PayBill> payBillLambdaQueryWrapper =
                Wrappers.lambdaQuery(PayBill.class).eq(PayBill::getOutOrderNo, params.get("out_trade_no"));
        PayBill payBill = payBillMapper.selectOne(payBillLambdaQueryWrapper);
        if (Objects.isNull(payBill)) {
            log.error("账单为空 notifyDto : {}",JSON.toJSONString(notifyDto));
            notifyVo.setPayResult(ALIPAY_NOTIFY_FAILURE_RESULT);
            return notifyVo;
        }
        if (Objects.equals(payBill.getPayBillStatus(), PayBillStatus.PAY.getCode())) {
            log.info("账单已支付 notifyDto : {}",JSON.toJSONString(notifyDto));
            notifyVo.setOutTradeNo(payBill.getOutOrderNo());
            notifyVo.setPayResult(ALIPAY_NOTIFY_SUCCESS_RESULT);
            return notifyVo;
        }
        if (Objects.equals(payBill.getPayBillStatus(), PayBillStatus.CANCEL.getCode())) {
            log.warn("拒绝取消后的支付回调 outOrderNo:{}", payBill.getOutOrderNo());
            // 不能确认回调，否则渠道不会重试且 CANCEL -> PAY 的状态冲突会被静默隐藏。
            notifyVo.setPayResult(ALIPAY_NOTIFY_FAILURE_RESULT);
            return notifyVo;
        }
        if (Objects.equals(payBill.getPayBillStatus(), PayBillStatus.REFUND.getCode())) {
            log.warn("拒绝退款后的支付回调 outOrderNo:{}", payBill.getOutOrderNo());
            notifyVo.setPayResult(ALIPAY_NOTIFY_FAILURE_RESULT);
            return notifyVo;
        }
        boolean dataVerify = payStrategyHandler.dataVerify(notifyDto.getParams(), payBill);
        if (!dataVerify) {
            notifyVo.setPayResult(ALIPAY_NOTIFY_FAILURE_RESULT);
            return notifyVo;
        }
        PayBill updatePayBill = new PayBill();
        updatePayBill.setPayBillStatus(PayBillStatus.PAY.getCode());
        LambdaUpdateWrapper<PayBill> payBillLambdaUpdateWrapper = Wrappers.lambdaUpdate(PayBill.class)
                .eq(PayBill::getOutOrderNo, params.get("out_trade_no"))
                .eq(PayBill::getPayBillStatus, PayBillStatus.NO_PAY.getCode());
        int updated = payBillMapper.update(updatePayBill, payBillLambdaUpdateWrapper);
        if (updated != 1) {
            // 并发取消/退款赢得 CAS 时必须交由渠道重试/人工对账，不能返回成功。
            notifyVo.setPayResult(ALIPAY_NOTIFY_FAILURE_RESULT);
            return notifyVo;
        }
        notifyVo.setOutTradeNo(payBill.getOutOrderNo());
        notifyVo.setPayResult(ALIPAY_NOTIFY_SUCCESS_RESULT);
        return notifyVo;
    }
    
    @ServiceLock(name = TRADE_CHECK,keys = {"#tradeCheckDto.outTradeNo"})
    public TradeCheckVo tradeCheck(TradeCheckDto tradeCheckDto) {
        TradeCheckVo tradeCheckVo = new TradeCheckVo();
        PayStrategyHandler payStrategyHandler = payStrategyContext.get(tradeCheckDto.getChannel());
        TradeResult tradeResult = payStrategyHandler.queryTrade(tradeCheckDto.getOutTradeNo());
        BeanUtil.copyProperties(tradeResult,tradeCheckVo);
        if (!tradeResult.isSuccess()) {
            return tradeCheckVo;
        }
        BigDecimal totalAmount = tradeResult.getTotalAmount();
        String outTradeNo = tradeResult.getOutTradeNo();
        Integer payBillStatus = tradeResult.getPayBillStatus();
        PayBill payBill = payBillMapper.selectOne(Wrappers.lambdaQuery(PayBill.class)
                .eq(PayBill::getOutOrderNo, outTradeNo));
        if (Objects.isNull(payBill)) {
            log.error("账单为空 tradeCheckDto : {}",JSON.toJSONString(tradeCheckDto));
            return tradeCheckVo;
        }
        if (payBill.getPayAmount().compareTo(totalAmount) != 0) {
            log.error("支付渠道 和库中账单支付金额不一致 支付渠道支付金额 : {}, 库中账单支付金额 : {}, tradeCheckDto : {}",
                    totalAmount,payBill.getPayAmount(),JSON.toJSONString(tradeCheckDto));
            return tradeCheckVo;
        }
        if (!Objects.equals(payBill.getPayBillStatus(), payBillStatus)) {
            log.warn("支付渠道和库中账单交易状态不一致 支付渠道payBillStatus : {}, 库中payBillStatus : {}, tradeCheckDto : {}",
                    payBillStatus,payBill.getPayBillStatus(),JSON.toJSONString(tradeCheckDto));
            transactionTemplate.executeWithoutResult(status -> reconcilePayBill(outTradeNo, payBillStatus));
        }
        return tradeCheckVo;
    }

    private void reconcilePayBill(String outTradeNo, Integer channelStatus) {
        // 渠道查询只能确认 NO_PAY -> PAY/CANCEL；不能把 PAY/REFUND 等终态回退或互相覆盖。
        if (!Objects.equals(channelStatus, PayBillStatus.PAY.getCode())
                && !Objects.equals(channelStatus, PayBillStatus.CANCEL.getCode())) {
            return;
        }
        PayBill update = new PayBill();
        update.setPayBillStatus(channelStatus);
        int changed = payBillMapper.update(update, Wrappers.lambdaUpdate(PayBill.class)
                .eq(PayBill::getOutOrderNo, outTradeNo)
                .eq(PayBill::getPayBillStatus, PayBillStatus.NO_PAY.getCode()));
        if (changed == 0) {
            PayBill latest = payBillMapper.selectOne(Wrappers.lambdaQuery(PayBill.class)
                    .eq(PayBill::getOutOrderNo, outTradeNo));
            if (latest != null && !Objects.equals(latest.getPayBillStatus(), channelStatus)) {
                log.warn("拒绝支付账单终态覆盖 outTradeNo:{} current:{} channel:{}",
                        outTradeNo, latest.getPayBillStatus(), channelStatus);
            }
        }
    }
    
    @ServiceLock(name = REFUND_PAYMENT, keys = {"#refundDto.orderNumber"})
    public String refund(RefundDto refundDto) {
        RefundPreparation preparation = transactionTemplate.execute(status -> prepareRefund(refundDto));
        if (preparation == null) throw new IllegalStateException("refund preparation returned null");
        if (preparation.completed()) return preparation.intent().getRefundNo();

        RefundResult refundResult;
        try {
            PayStrategyHandler handler = payStrategyContext.get(refundDto.getChannel());
            // 外部渠道调用明确位于数据库事务之外；refundNo 在之前的事务中已持久化。
            refundResult = handler.refund(refundDto.getOrderNumber(), refundDto.getAmount(),
                    refundDto.getReason(), preparation.intent().getRefundNo());
        } catch (RuntimeException ex) {
            markRefundFailed(preparation.intent(), ex.getMessage());
            throw ex;
        }
        if (!refundResult.isSuccess()) {
            markRefundFailed(preparation.intent(), refundResult.getMessage());
            throw new StellarisFrameException(refundResult.getMessage());
        }
        transactionTemplate.executeWithoutResult(status -> completeRefund(preparation, refundDto));
        return preparation.intent().getRefundNo();
    }

    private RefundPreparation prepareRefund(RefundDto dto) {
        PayBill payBill = payBillMapper.selectOne(Wrappers.lambdaQuery(PayBill.class)
                .eq(PayBill::getOutOrderNo, dto.getOrderNumber()));
        if (payBill == null) throw new StellarisFrameException(BaseCode.PAY_BILL_NOT_EXIST);
        RefundIntent intent = refundIntentMapper.selectOne(Wrappers.lambdaQuery(RefundIntent.class)
                .eq(RefundIntent::getOutOrderNo, dto.getOrderNumber()));
        if (intent != null) {
            if (intent.getAmount().compareTo(dto.getAmount()) != 0 || !Objects.equals(intent.getChannel(), dto.getChannel())) {
                throw new StellarisFrameException("同一订单的退款金额或渠道与已持久化Intent不一致");
            }
            if ("SUCCEEDED".equals(intent.getIntentStatus())) return new RefundPreparation(payBill, intent, true);
            if ("PROCESSING".equals(intent.getIntentStatus())) {
                throw new StellarisFrameException("退款正在处理中");
            }
        }
        if (!Objects.equals(payBill.getPayBillStatus(), PayBillStatus.PAY.getCode())) {
            throw new StellarisFrameException(BaseCode.PAY_BILL_IS_NOT_PAY_STATUS);
        }
        if (dto.getAmount().compareTo(payBill.getPayAmount()) != 0) {
            throw new StellarisFrameException(BaseCode.REFUND_AMOUNT_GREATER_THAN_PAY_AMOUNT.getCode(),
                    "当前退款状态机仅支持全额退款，退款金额必须等于支付金额");
        }
        Date now = DateUtils.now();
        if (intent == null) {
            intent = new RefundIntent();
            intent.setId(uidGenerator.getUid());
            intent.setRefundNo(String.valueOf(uidGenerator.getUid()));
            intent.setOutOrderNo(dto.getOrderNumber());
            intent.setPayBillId(payBill.getId());
            intent.setAmount(dto.getAmount());
            intent.setChannel(dto.getChannel());
            intent.setReason(dto.getReason());
            intent.setIntentStatus("PROCESSING");
            intent.setRetryCount(1);
            intent.setCreateTime(now);
            intent.setEditTime(now);
            intent.setStatus(1);
            refundIntentMapper.insert(intent);
        } else {
            String current = intent.getIntentStatus();
            if ("DEAD".equals(current) || Objects.requireNonNullElse(intent.getRetryCount(), 0) >= refundMaxAttempts) {
                throw new StellarisFrameException("退款已达到最大重试次数，需要人工核对渠道结果");
            }
            if (!"FAILED".equals(current) && !"PENDING".equals(current)) {
                throw new StellarisFrameException("退款Intent状态不可重试: " + current);
            }
            RefundIntent claim = new RefundIntent();
            claim.setIntentStatus("PROCESSING");
            claim.setRetryCount(Objects.requireNonNullElse(intent.getRetryCount(), 0) + 1);
            claim.setLastError("");
            claim.setEditTime(now);
            int claimed = refundIntentMapper.update(claim, Wrappers.lambdaUpdate(RefundIntent.class)
                    .eq(RefundIntent::getId, intent.getId()).eq(RefundIntent::getIntentStatus, current));
            if (claimed != 1) throw new StellarisFrameException("退款重试正在由其他节点处理");
            intent.setIntentStatus("PROCESSING");
            intent.setRetryCount(claim.getRetryCount());
        }
        return new RefundPreparation(payBill, intent, false);
    }

    private void completeRefund(RefundPreparation preparation, RefundDto dto) {
        PayBill updatePayBill = new PayBill();
        updatePayBill.setPayBillStatus(PayBillStatus.REFUND.getCode());
        int payUpdated = payBillMapper.update(updatePayBill, Wrappers.lambdaUpdate(PayBill.class)
                .eq(PayBill::getId, preparation.payBill().getId())
                .eq(PayBill::getOutOrderNo, preparation.payBill().getOutOrderNo())
                .eq(PayBill::getPayBillStatus, PayBillStatus.PAY.getCode()));
        if (payUpdated != 1) {
            PayBill latest = payBillMapper.selectOne(Wrappers.lambdaQuery(PayBill.class)
                    .eq(PayBill::getOutOrderNo, preparation.payBill().getOutOrderNo()));
            if (latest == null || !Objects.equals(latest.getPayBillStatus(), PayBillStatus.REFUND.getCode())) {
                throw new IllegalStateException("refund succeeded at channel but pay bill CAS failed");
            }
        }
        RefundBill existing = refundBillMapper.selectOne(Wrappers.lambdaQuery(RefundBill.class)
                .eq(RefundBill::getOutOrderNo, preparation.payBill().getOutOrderNo()));
        if (existing == null) {
            Date now = DateUtils.now();
            RefundBill refundBill = new RefundBill();
            refundBill.setId(uidGenerator.getUid());
            refundBill.setRefundNo(preparation.intent().getRefundNo());
            refundBill.setOutOrderNo(preparation.payBill().getOutOrderNo());
            refundBill.setPayBillId(preparation.payBill().getId());
            refundBill.setRefundAmount(dto.getAmount());
            refundBill.setRefundStatus(2);
            refundBill.setRefundTime(now);
            refundBill.setReason(dto.getReason());
            refundBill.setCreateTime(now);
            refundBill.setEditTime(now);
            refundBill.setStatus(1);
            refundBillMapper.insert(refundBill);
        }
        RefundIntent complete = new RefundIntent();
        complete.setIntentStatus("SUCCEEDED");
        complete.setLastError("");
        complete.setEditTime(DateUtils.now());
        int updated = refundIntentMapper.update(complete, Wrappers.lambdaUpdate(RefundIntent.class)
                .eq(RefundIntent::getId, preparation.intent().getId())
                .eq(RefundIntent::getIntentStatus, "PROCESSING"));
        if (updated != 1) throw new IllegalStateException("refund intent completion CAS failed");
    }

    private void markRefundFailed(RefundIntent intent, String error) {
        transactionTemplate.executeWithoutResult(status -> {
            RefundIntent failed = new RefundIntent();
            int attempts = Objects.requireNonNullElse(intent.getRetryCount(), 1);
            failed.setIntentStatus(attempts >= refundMaxAttempts ? "DEAD" : "FAILED");
            failed.setNextRetryTime(new Date(System.currentTimeMillis()
                    + refundRetryBackoffMs * Math.min(attempts, 10)));
            failed.setLastError(abbreviate(error));
            failed.setEditTime(DateUtils.now());
            refundIntentMapper.update(failed, Wrappers.lambdaUpdate(RefundIntent.class)
                    .eq(RefundIntent::getId, intent.getId())
                    .eq(RefundIntent::getIntentStatus, "PROCESSING"));
        });
    }

    private String abbreviate(String error) {
        String value = error == null || error.isBlank() ? "unknown channel error" : error;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private record RefundPreparation(PayBill payBill, RefundIntent intent, boolean completed) {}
    
    public PayBillVo detail(PayBillDto payBillDto) {
        PayBillVo payBillVo = new PayBillVo();
        LambdaQueryWrapper<PayBill> payBillLambdaQueryWrapper =
                Wrappers.lambdaQuery(PayBill.class).eq(PayBill::getOutOrderNo, payBillDto.getOrderNumber());
        PayBill payBill = payBillMapper.selectOne(payBillLambdaQueryWrapper);
        if (Objects.nonNull(payBill)) {
            BeanUtil.copyProperties(payBill,payBillVo);
        }
        return payBillVo;
    }
}
