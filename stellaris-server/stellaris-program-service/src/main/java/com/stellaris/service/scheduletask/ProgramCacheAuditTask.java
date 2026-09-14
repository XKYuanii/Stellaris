package com.stellaris.service.scheduletask;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellaris.core.RedisKeyManage;
import com.stellaris.entity.Program;
import com.stellaris.mapper.ProgramMapper;
import com.stellaris.redis.RedisCache;
import com.stellaris.redis.RedisKeyBuild;
import com.stellaris.service.ProgramService;
import com.stellaris.vo.ProgramVo;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 节目详情缓存对账。
 *
 * <p>Cache Aside 的「删缓存」不属于数据库事务：写库成功但删除失败（Redis 抖动、
 * 网络超时、实例在两步之间退出）时，旧值会一直脏到 TTL 过期，而且没有任何机制
 * 会发现。本任务低频抽样比对缓存与数据库的节目状态，不一致即定向失效。
 *
 * <p>只删不回写：回写需要重建完整 {@link ProgramVo}，等于在对账路径上复制一份
 * 读路径逻辑，既难维护又多一处可能写错的地方。删掉之后由下一次读请求按正常回源
 * 重建，路径唯一。
 *
 * <p>按 editTime 倒序取样，而不是随机取样：最近被修改过的节目正是「刚写完库、
 * 删缓存可能失败」的窗口所在，命中率远高于均匀抽样。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "program.cache-audit", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class ProgramCacheAuditTask {

    private final ProgramMapper programMapper;
    private final ProgramService programService;
    private final RedisCache redisCache;
    private final MeterRegistry meterRegistry;

    @Value("${program.cache-audit.sample-size:50}")
    private int sampleSize;

    public ProgramCacheAuditTask(ProgramMapper programMapper, ProgramService programService,
                                 RedisCache redisCache, MeterRegistry meterRegistry) {
        this.programMapper = programMapper;
        this.programService = programService;
        this.redisCache = redisCache;
        this.meterRegistry = meterRegistry;
    }

    /** 默认五分钟一轮。这是兜底对账，不是实时一致性手段，频率高了只会白白打库。 */
    @Scheduled(fixedDelayString = "${program.cache-audit.fixed-delay-ms:300000}")
    public void audit() {
        int inspected = 0;
        int repaired = 0;
        try {
            List<Program> sample = programMapper.selectList(Wrappers.lambdaQuery(Program.class)
                    .select(Program::getId, Program::getProgramStatus)
                    .orderByDesc(Program::getEditTime)
                    .last("LIMIT " + Math.max(1, sampleSize)));
            for (Program program : sample) {
                if (program.getId() == null) {
                    continue;
                }
                // 缓存不存在不算不一致：Cache Aside 允许缺失，下次读自然回源。
                ProgramVo cached = redisCache.get(
                        RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM, program.getId()), ProgramVo.class);
                if (cached == null) {
                    continue;
                }
                inspected++;
                if (Objects.equals(cached.getProgramStatus(), program.getProgramStatus())) {
                    continue;
                }
                // 已下架但缓存仍显示上架是最危险的一种：用户能看到并尝试下单。
                log.warn("节目缓存与数据库不一致，执行定向失效 programId:{} cached:{} db:{}",
                        program.getId(), cached.getProgramStatus(), program.getProgramStatus());
                programService.evictProgramDetailCache(program.getId());
                repaired++;
            }
        } catch (RuntimeException ex) {
            // 对账失败不能影响主链路，也不能让调度线程因异常停摆。
            meterRegistry.counter("stellaris_program_cache_audit_total", "result", "failed").increment();
            log.error("节目缓存对账执行失败", ex);
            return;
        }
        meterRegistry.counter("stellaris_program_cache_audit_inspected_total").increment(inspected);
        meterRegistry.counter("stellaris_program_cache_audit_repaired_total").increment(repaired);
        meterRegistry.counter("stellaris_program_cache_audit_total", "result", "success").increment();
        if (repaired > 0) {
            log.warn("节目缓存对账完成 inspected:{} repaired:{}", inspected, repaired);
        } else {
            log.debug("节目缓存对账完成 inspected:{} repaired:0", inspected);
        }
    }
}
