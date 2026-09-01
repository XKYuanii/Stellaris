package com.stellaris.service.delaylease;

import com.alibaba.fastjson.JSON;
import com.stellaris.dto.OrderCancelDto;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellaris.entity.Order;
import com.stellaris.enums.OrderStatus;
import com.stellaris.mapper.OrderMapper;
import com.stellaris.service.OrderService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 取消成功才 ACK；异常不 ACK，lease 到期后由其他节点重领。 */
@Component @Slf4j
public class DelayCancelLeaseWorker {
 private final DelayCancelLeaseQueue queue; private final OrderService orderService; private final OrderMapper orderMapper;
 private final java.util.concurrent.atomic.AtomicLong dbScanCursor = new java.util.concurrent.atomic.AtomicLong();
 @Value("${delay-cancel-lease.enabled:false}") private boolean enabled;
 @Value("${delay-cancel-lease.timeout-ms:900000}") private long timeoutMs;
 @Value("${delay-cancel-lease.lease-ms:120000}") private long leaseMs;
 @Value("${delay-cancel-lease.max-attempts:10}") private int maxAttempts;
 @Value("${delay-cancel-lease.retry-backoff-ms:5000}") private long retryBackoffMs;
 public DelayCancelLeaseWorker(DelayCancelLeaseQueue queue, OrderService orderService, OrderMapper orderMapper){this.queue=queue;this.orderService=orderService;this.orderMapper=orderMapper;}
 @Scheduled(fixedDelayString="${delay-cancel-lease.fixed-delay-ms:1000}")
 public void consume(){
  if(!enabled)return;
  long now=System.currentTimeMillis();
  queue.requeueExpired(now,100,maxAttempts,retryBackoffMs);
  for(String id:queue.claim(now,leaseMs,100)) {
   try {
    Task task=JSON.parseObject(queue.payload(id),Task.class);
    if(task==null||task.orderNumber==null) throw new IllegalArgumentException("missing delay-cancel task payload");
    Order order=orderMapper.selectOne(Wrappers.lambdaQuery(Order.class).eq(Order::getOrderNumber,task.orderNumber));
    if(order==null) throw new IllegalStateException("order is not visible yet: "+task.orderNumber);
    if(!java.util.Objects.equals(order.getOrderStatus(),OrderStatus.NO_PAY.getCode())) { queue.ack(id); continue; }
    OrderCancelDto dto=new OrderCancelDto();dto.setOrderNumber(task.orderNumber);
    orderService.cancel(dto);
    queue.ack(id);
   } catch(Exception e){
    String result=queue.fail(id,System.currentTimeMillis(),maxAttempts,retryBackoffMs);
    log.warn("delay cancel lease task failed id:{} result:{}",id,result,e);
   }
  }
 }
 @Data public static class Task { private Long orderNumber; private Long programId; }

 @Scheduled(fixedDelayString="${delay-cancel-lease.db-scan-fixed-delay-ms:60000}") public void scanDatabaseFallback(){
  if(!enabled)return; long cutoff=System.currentTimeMillis()-timeoutMs;
  java.util.List<Order> orders=orderMapper.selectList(Wrappers.lambdaQuery(Order.class)
          .eq(Order::getOrderStatus,OrderStatus.NO_PAY.getCode())
          .le(Order::getCreateOrderTime,new java.util.Date(cutoff))
          .gt(dbScanCursor.get()>0,Order::getId,dbScanCursor.get()).orderByAsc(Order::getId).last("LIMIT 100"));
  if(orders.isEmpty()&&dbScanCursor.get()>0){dbScanCursor.set(0);orders=orderMapper.selectList(Wrappers.lambdaQuery(Order.class)
          .eq(Order::getOrderStatus,OrderStatus.NO_PAY.getCode())
          .le(Order::getCreateOrderTime,new java.util.Date(cutoff)).orderByAsc(Order::getId).last("LIMIT 100"));}
  for(Order order:orders){
   Task task=new Task();task.setOrderNumber(order.getOrderNumber());task.setProgramId(order.getProgramId());queue.enqueue("order-cancel:"+order.getOrderNumber(),JSON.toJSONString(task),System.currentTimeMillis());
  }
  if(!orders.isEmpty())dbScanCursor.set(orders.get(orders.size()-1).getId());
 }
}
