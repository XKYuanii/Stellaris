package com.stellaris.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellaris.core.RedisKeyManage;
import com.stellaris.dto.RuleDto;
import com.stellaris.dto.RuleGetDto;
import com.stellaris.dto.RuleStatusDto;
import com.stellaris.dto.RuleUpdateDto;
import com.stellaris.entity.DepthRule;
import com.stellaris.entity.Rule;
import com.stellaris.enums.RuleStatus;
import com.stellaris.mapper.DepthRuleMapper;
import com.stellaris.mapper.RuleMapper;
import com.stellaris.redis.RedisCache;
import com.stellaris.redis.RedisKeyBuild;
import com.stellaris.vo.RuleVo;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 普通规则 service
 * @author: xz_y
 **/
@Service
public class RuleService {

    @Autowired
    private RuleMapper ruleMapper;
    
    @Autowired
    private RedisCache redisCache;
    
    @Autowired
    private DepthRuleMapper depthRuleMapper;
    
    @Autowired
    private UidGenerator uidGenerator;
    
    @Transactional(rollbackFor = Exception.class)
    public void ruleAdd(RuleDto ruleDto) {
        add(ruleDto);
        saveAllRuleCache();
    }
    @Transactional(rollbackFor = Exception.class)
    public Long add(RuleDto ruleDto) {
        delAll();
        Rule rule = new Rule();
        BeanUtils.copyProperties(ruleDto,rule);
        rule.setId(uidGenerator.getUid());
        rule.setCreateTime(DateUtil.date());
        ruleMapper.insert(rule);
        return rule.getId();
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void ruleUpdate(final RuleUpdateDto ruleUpdateDto) {
        update(ruleUpdateDto);
        saveAllRuleCache();
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void update(final RuleUpdateDto ruleUpdateDto) {
        Rule rule = new Rule();
        BeanUtils.copyProperties(ruleUpdateDto,rule);
        ruleMapper.updateById(rule);
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void ruleUpdateStatus(final RuleStatusDto ruleStatusDto) {
        updateStatus(ruleStatusDto);
        saveAllRuleCache();
    }
    
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(final RuleStatusDto ruleStatusDto) {
        Rule rule = new Rule();
        rule.setId(ruleStatusDto.getId());
        rule.setStatus(ruleStatusDto.getStatus());
        ruleMapper.updateById(rule);
        saveAllRuleCache();
    }
    
    public RuleVo get(final RuleGetDto ruleGetDto) {
        RuleVo ruleVo = new RuleVo();
        Optional.ofNullable(ruleMapper.selectById(ruleGetDto.getId())).ifPresent(rule -> {
            BeanUtils.copyProperties(rule,ruleVo);
        });
        return ruleVo;
    }
    
    public RuleVo get() {
        RuleVo ruleVo = new RuleVo();
        Optional.ofNullable(ruleMapper.selectOne(null)).ifPresent(rule -> {
            BeanUtils.copyProperties(rule,ruleVo);
        });
        return ruleVo;
    }
    
    public void delAll(){
        ruleMapper.delAll();
    }
    
    
    public void saveAllRuleCache(){
        Map<String, Object> map = new HashMap<>(2);
        
        LambdaQueryWrapper<Rule> ruleQueryWrapper = Wrappers.lambdaQuery(Rule.class).eq(Rule::getStatus,RuleStatus.RUN.getCode());
        Rule rule = ruleMapper.selectOne(ruleQueryWrapper);
        if (Optional.ofNullable(rule).isPresent()) {
            map.put(RedisKeyBuild.createRedisKey(RedisKeyManage.RULE).getRelKey(),rule);
        }
        LambdaQueryWrapper<DepthRule> depthRuleQueryWrapper = Wrappers.lambdaQuery(DepthRule.class).eq(DepthRule::getStatus,RuleStatus.RUN.getCode());
        List<DepthRule> depthRules = depthRuleMapper.selectList(depthRuleQueryWrapper);
        if (CollUtil.isNotEmpty(depthRules)) {
            map.put(RedisKeyBuild.createRedisKey(RedisKeyManage.DEPTH_RULE).getRelKey(),depthRules);
        }
        redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.ALL_RULE_HASH));
        if (map.size() > 0 && Objects.nonNull(map.get(RedisKeyBuild.createRedisKey(RedisKeyManage.RULE).getRelKey()))) {
            redisCache.putHash(RedisKeyBuild.createRedisKey(RedisKeyManage.ALL_RULE_HASH),map);
        }
    }
}
