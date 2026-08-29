package com.stellaris.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import com.stellaris.util.StringUtil;
import com.stellaris.dto.AllRuleDto;
import com.stellaris.dto.DepthRuleDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.enums.RuleStatus;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.vo.AllDepthRuleVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 所有规则 service
 * @author: 阿星不是程序员
 **/
@Service
public class AllRuleService {
    
    @Autowired
    private RuleService ruleService;
    
    @Autowired
    private DepthRuleService depthRuleService;
    
    @Transactional(rollbackFor = Exception.class)
    public void add(final AllRuleDto allRuleDto) {
        ruleService.add(allRuleDto.getRuleDto());
        depthRuleService.delAll();
        List<DepthRuleDto> depthRuleDtoList = allRuleDto.getDepthRuleDtoList();
        if (CollUtil.isNotEmpty(depthRuleDtoList)) {
            for (int i = 0; i < depthRuleDtoList.size(); i++) {
                DepthRuleDto depthRuleDto = depthRuleDtoList.get(i);
                checkTime(depthRuleDto.getStartTimeWindow(),depthRuleDto.getEndTimeWindow(),filterDepthRuleDtoList(depthRuleDtoList,i));
                depthRuleService.add(depthRuleDto);
            }
        }
        ruleService.saveAllRuleCache();
    }
    
    public void checkTime(String startTimeWindow, String endTimeWindow, List<DepthRuleDto> depthRuleDtoList){
        if (StringUtil.isEmpty(startTimeWindow) || StringUtil.isEmpty(endTimeWindow)) {
            return;
        }
        depthRuleDtoList = depthRuleDtoList.stream().filter(depthRuleDto -> {
            if (depthRuleDto.getStatus() != null) {
                if (depthRuleDto.getStatus().equals(RuleStatus.RUN.getCode())) {
                    return true;
                }else {
                    return false;
                }
            }else {
                return true;
            }
        }).collect(Collectors.toList());
        for (final DepthRuleDto depthRuleDto : depthRuleDtoList) {
            long checkStartTimeWindowTimestamp = getTimeWindowTimestamp(startTimeWindow);
            long checkEndTimeWindowTimestamp = getTimeWindowTimestamp(endTimeWindow);
            long startTimeWindowTimestamp = getTimeWindowTimestamp(depthRuleDto.getStartTimeWindow());
            long endTimeWindowTimestamp = getTimeWindowTimestamp(depthRuleDto.getEndTimeWindow());
            boolean checkStartLimitTimeResult = checkStartTimeWindowTimestamp >= startTimeWindowTimestamp && checkStartTimeWindowTimestamp <= endTimeWindowTimestamp;
            boolean checkEndLimitTimeResult = checkEndTimeWindowTimestamp >= startTimeWindowTimestamp && checkEndTimeWindowTimestamp <= endTimeWindowTimestamp;
            if (checkStartLimitTimeResult || checkEndLimitTimeResult) {
                throw new StellarisFrameException(BaseCode.API_RULE_TIME_WINDOW_INTERSECT);
            }
        }
    }
    
    public List<DepthRuleDto> filterDepthRuleDtoList(List<DepthRuleDto> depthRuleDtoList, int coord){
        List<DepthRuleDto> fiterDepthRuleDtoList = new ArrayList<>();
        for (int i = 0; i < depthRuleDtoList.size(); i++) {
            if (i != coord) {
                fiterDepthRuleDtoList.add(depthRuleDtoList.get(i));
            }
        }
        return fiterDepthRuleDtoList;
    }
    public long getTimeWindowTimestamp(String timeWindow){
        String today = DateUtil.today();
        return DateUtil.parse(today + " " + timeWindow).getTime();
    }
    
    public AllDepthRuleVo get() {
        AllDepthRuleVo allDepthRuleVo = new AllDepthRuleVo();
        allDepthRuleVo.setRuleVo(ruleService.get());
        allDepthRuleVo.setDepthRuleVoList(depthRuleService.selectList());
        return allDepthRuleVo;
    }
}
