package com.stellaris.service.handler;

import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellaris.core.RedisKeyManage;
import com.stellaris.domain.ProgramRecord;
import com.stellaris.entity.Order;
import com.stellaris.entity.OrderProgram;
import com.stellaris.entity.OrderTicketUser;
import com.stellaris.entity.OrderTicketUserRecord;
import com.stellaris.enums.HandleStatus;
import com.stellaris.enums.ReconciliationStatus;
import com.stellaris.mapper.OrderMapper;
import com.stellaris.mapper.OrderProgramMapper;
import com.stellaris.mapper.OrderTicketUserMapper;
import com.stellaris.mapper.OrderTicketUserRecordMapper;
import com.stellaris.redis.RedisCache;
import com.stellaris.redis.RedisKeyBuild;
import com.stellaris.util.SplitUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static com.stellaris.constant.Constant.GLIDE_LINE;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 记录流水处理
 * @author: xz_y
 **/
@Slf4j
@Component
public class ProgramRecordHandler {

    @Autowired
    private RedisCache redisCache;
    
    @Autowired
    private OrderMapper orderMapper;
    
    @Autowired
    private OrderTicketUserMapper orderTicketUserMapper;
    
    @Autowired
    private OrderTicketUserRecordMapper orderTicketUserRecordMapper;
    
    @Autowired
    private OrderProgramMapper orderProgramMapper;
    
    /**
     * 向redis中添加补偿的记录，从未完成记录中转移到完整的记录
     * */
    @Transactional(rollbackFor = Exception.class)
    public void add(Long programId,
                    Map<String, ProgramRecord> completeRedisCordMap,
                    Map<String, String> totalProgramRecordMap){
        Set<String> keyList = new HashSet<>();
        // 把数据库中的订单、购票人订单、购票人订单记录都修改成对账完成状态。
        addKeyList(keyList,completeRedisCordMap);
        addKeyList(keyList,totalProgramRecordMap);
        for (final String key : keyList) {
            String[] split = SplitUtil.toSplit(key);
            Long identifierId = Long.valueOf(split[0]);
            Long userId = Long.valueOf(split[1]);
            int result = updateDbOrderTicketUserRecordStatus(programId, identifierId, userId,
                    ReconciliationStatus.RECONCILIATION_SUCCESS);
            log.info("修改数据库记录流水成功, programId:{}, identifierId:{}, userId:{}, result:{}",
                    programId, identifierId, userId, result);
        }
        if (CollectionUtil.isNotEmpty(totalProgramRecordMap)) {
            redisCache.delForHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_RECORD, programId),
                    totalProgramRecordMap.keySet());
            redisCache.putHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_RECORD_FINISH, programId),
                    totalProgramRecordMap);
        }
        if (CollectionUtil.isNotEmpty(completeRedisCordMap)) {
            redisCache.putHash(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_RECORD_FINISH, programId),
                    completeRedisCordMap);
            log.info("添加记录流水成功, programId:{}, completeRedisCordMap:{}, totalProgramRecordMap:{}",
                    programId, completeRedisCordMap, totalProgramRecordMap);
        }
    }
    
    public void addKeyList(Set<String> keyList,Map<String,?> map){
        if (CollectionUtil.isEmpty(map)) {
            return;
        }
        for (final Entry<String, ?> entry : map.entrySet()) {
            String[] split = SplitUtil.toSplit(entry.getKey());
            keyList.add(split[1] + GLIDE_LINE + split[2]);
        }
    }
    
    @Transactional(rollbackFor = Exception.class)
    public int updateDbOrderTicketUserRecordStatus(Long programId, Long identifierId, Long userId, ReconciliationStatus reconciliationStatus) {
        List<Order> orderList = orderMapper.selectList(Wrappers.lambdaQuery(Order.class).eq(Order::getProgramId, programId).eq(Order::getIdentifierId, identifierId).eq(Order::getUserId, userId).eq(Order::getReconciliationStatus, ReconciliationStatus.RECONCILIATION_NO.getCode()));
        if (CollectionUtil.isEmpty(orderList)) {
            return 0;
        }
        Order updateOrder = new Order();
        updateOrder.setReconciliationStatus(reconciliationStatus.getCode());
        //将订单的对账状态更新为已对账
        orderMapper.update(updateOrder, Wrappers.lambdaUpdate(Order.class)
                .eq(Order::getProgramId, programId)
                .eq(Order::getIdentifierId, identifierId)
                .eq(Order::getUserId, userId)
                .eq(Order::getReconciliationStatus, ReconciliationStatus.RECONCILIATION_NO.getCode()));
        Long orderNumber = orderList.get(0).getOrderNumber();
        //将购票人订单的对账状态更新为已对账
        OrderTicketUser updateOrderTicketUser = new OrderTicketUser();
        updateOrderTicketUser.setReconciliationStatus(reconciliationStatus.getCode());
        orderTicketUserMapper.update(updateOrderTicketUser,Wrappers.lambdaUpdate(OrderTicketUser.class)
                .eq(OrderTicketUser::getOrderNumber, orderNumber)
                .eq(OrderTicketUser::getReconciliationStatus, ReconciliationStatus.RECONCILIATION_NO.getCode()));
        //将订单节目的对账状态更新为已对账
        OrderProgram updateOrderProgram = new OrderProgram();
        updateOrderProgram.setHandleStatus(HandleStatus.YES_HANDLE.getCode());
        orderProgramMapper.update(updateOrderProgram,Wrappers.lambdaUpdate(OrderProgram.class)
                .eq(OrderProgram::getOrderNumber, orderNumber)
                .eq(OrderProgram::getHandleStatus, HandleStatus.NO_HANDLE.getCode())
                .eq(OrderProgram::getProgramId, programId));
        //将购票人订单记录的对账状态更新为已对账
        OrderTicketUserRecord updateOrderTicketUserRecord = new OrderTicketUserRecord();
        updateOrderTicketUserRecord.setReconciliationStatus(reconciliationStatus.getCode());
        return orderTicketUserRecordMapper.update(updateOrderTicketUserRecord,Wrappers.lambdaUpdate(OrderTicketUserRecord.class)
                .eq(OrderTicketUserRecord::getOrderNumber, orderNumber)
                .eq(OrderTicketUserRecord::getReconciliationStatus, ReconciliationStatus.RECONCILIATION_NO.getCode()));
    }
}
