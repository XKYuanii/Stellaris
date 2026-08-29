package com.stellaris.service.handler;

import com.stellaris.core.RedisKeyManage;
import com.stellaris.redis.RedisCache;
import com.stellaris.redis.RedisKeyBuild;
import com.stellaris.servicelock.LockType;
import com.stellaris.servicelock.annotion.ServiceLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.stellaris.core.DistributedLockConstants.SEAT_LOCK;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 座位处理
 * @author: 阿星不是程序员
 **/
@Slf4j
@Component
public class SeatHandler {
    
    @Autowired
    private RedisCache redisCache;

    /**
     * 从redis中删除座位数据
     * */
    @ServiceLock(lockType= LockType.Write,name = SEAT_LOCK,keys = {"#programId","#ticketCategoryId"})
    public void delRedisSeatData(Long programId,Long ticketCategoryId){
        List<RedisKeyBuild> keyList = new ArrayList<>();
        keyList.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH,programId,ticketCategoryId));
        keyList.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH,programId,ticketCategoryId));
        keyList.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_SOLD_RESOLUTION_HASH,programId,ticketCategoryId));
        redisCache.del(keyList);
    }
}
