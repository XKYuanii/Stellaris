package com.stellaris.toolkit;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import jakarta.annotation.PreDestroy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 为 Snowflake 节点分配 Redis 租约，并在租约丢失后让本地发号 fail-closed。 */
@Slf4j
public class WorkAndDataCenterIdHandler {

    private static final String SNOWFLAKE_LEASE_KEY = "stellaris:{snowflake:node}:leases";
    private static final String SNOWFLAKE_OWNER_KEY = "stellaris:{snowflake:node}:owners";
    private static final List<String> KEYS = List.of(SNOWFLAKE_LEASE_KEY, SNOWFLAKE_OWNER_KEY);
    private static final long LEASE_MILLIS = 120_000L;
    private static final long RENEW_MILLIS = 30_000L;
    
    private final StringRedisTemplate stringRedisTemplate;
    
    private final String ownerToken = UUID.randomUUID().toString();
    private final AtomicBoolean renewalStarted = new AtomicBoolean();
    private final ScheduledExecutorService renewalExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "stellaris-snowflake-lease-renewal");
        thread.setDaemon(true);
        return thread;
    });
    private DefaultRedisScript<String> allocateScript;
    private DefaultRedisScript<Long> renewScript;
    private volatile WorkDataCenterId allocation;
    
    public WorkAndDataCenterIdHandler(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
        try {
            allocateScript = new DefaultRedisScript<>();
            allocateScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/workAndDataCenterId.lua")));
            allocateScript.setResultType(String.class);
            renewScript = new DefaultRedisScript<>();
            renewScript.setScriptSource(new ResourceScriptSource(
                    new ClassPathResource("lua/renewWorkAndDataCenterId.lua")));
            renewScript.setResultType(Long.class);
        } catch (Exception e) {
            log.error("redisScript init lua error",e);
        }
    }
    
    public synchronized WorkDataCenterId getWorkAndDataCenterId(){
        if (allocation != null) {
            return allocation;
        }
        try {
            Object[] data = new String[4];
            data[0] = String.valueOf(IdGeneratorConstant.MAX_WORKER_ID);
            data[1] = String.valueOf(IdGeneratorConstant.MAX_DATA_CENTER_ID);
            data[2] = String.valueOf(LEASE_MILLIS);
            data[3] = ownerToken;
            String result = stringRedisTemplate.execute(allocateScript, KEYS, data);
            WorkDataCenterId workDataCenterId = JSON.parseObject(result, WorkDataCenterId.class);
            if (workDataCenterId == null || workDataCenterId.getWorkId() == null
                    || workDataCenterId.getDataCenterId() == null
                    || workDataCenterId.getWorkId() < 0 || workDataCenterId.getDataCenterId() < 0) {
                throw new IllegalStateException("Snowflake node id space is exhausted");
            }
            workDataCenterId.refreshLease(LEASE_MILLIS);
            allocation = workDataCenterId;
            startRenewal();
            return workDataCenterId;
        }catch (Exception e) {
            log.error("getWorkAndDataCenterId error",e);
            throw new IllegalStateException("Unable to allocate a unique Snowflake node id", e);
        }
    }

    private void startRenewal() {
        if (renewalStarted.compareAndSet(false, true)) {
            renewalExecutor.scheduleWithFixedDelay(this::renewLease, RENEW_MILLIS, RENEW_MILLIS,
                    TimeUnit.MILLISECONDS);
        }
    }

    private void renewLease() {
        WorkDataCenterId current = allocation;
        if (current == null) {
            return;
        }
        long nodeId = current.getDataCenterId() * (IdGeneratorConstant.MAX_WORKER_ID + 1)
                + current.getWorkId();
        try {
            Long renewed = stringRedisTemplate.execute(renewScript, KEYS,
                    String.valueOf(nodeId), ownerToken, String.valueOf(LEASE_MILLIS));
            if (!Long.valueOf(1L).equals(renewed)) {
                current.invalidateLease();
                log.error("Snowflake node lease ownership was lost, nodeId:{}. ID generation is fenced", nodeId);
                return;
            }
            current.refreshLease(LEASE_MILLIS);
        } catch (RuntimeException ex) {
            // 保留上一次本地 deadline；短暂 Redis 抖动可继续服务，超过租约后生成器自动拒绝发号。
            log.error("Snowflake node lease renewal failed, nodeId:{}. ID generation will stop at lease expiry",
                    nodeId, ex);
        }
    }

    @PreDestroy
    public void shutdown() {
        renewalExecutor.shutdownNow();
    }
}
