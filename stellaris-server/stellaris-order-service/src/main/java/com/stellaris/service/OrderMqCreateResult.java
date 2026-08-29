package com.stellaris.service;

/** Kafka 创建订单完成结果；时间点位于订单事务提交后、回执缓存写入前。 */
public record OrderMqCreateResult(String orderNumber, long orderCreatedTime) {
}
