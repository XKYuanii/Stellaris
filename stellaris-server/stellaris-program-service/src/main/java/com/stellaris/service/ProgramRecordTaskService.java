package com.stellaris.service;


import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.stellaris.dto.ProgramRecordTaskAddDto;
import com.stellaris.dto.ProgramRecordTaskListDto;
import com.stellaris.dto.ProgramRecordTaskUpdateDto;
import com.stellaris.entity.ProgramRecordTask;
import com.stellaris.mapper.ProgramRecordTaskMapper;
import com.stellaris.util.DateUtils;
import com.stellaris.vo.ProgramRecordTaskVo;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目演出时间 service
 * @author: xz_y
 **/
@Service
public class ProgramRecordTaskService extends ServiceImpl<ProgramRecordTaskMapper, ProgramRecordTask> {
    
    @Autowired
    private UidGenerator uidGenerator;
    
    @Autowired
    private ProgramRecordTaskMapper programRecordTaskMapper;
    
    
    public List<ProgramRecordTaskVo> select(ProgramRecordTaskListDto programRecordTaskListDto){
        int limit = Math.max(1, Math.min(Objects.requireNonNullElse(programRecordTaskListDto.getLimit(), 200), 1000));
        List<ProgramRecordTask> programRecordTaskList = 
                programRecordTaskMapper.selectList(Wrappers.lambdaQuery(ProgramRecordTask.class)
                        .eq(ProgramRecordTask::getHandleStatus, programRecordTaskListDto.getHandleStatus())
                        .le(ProgramRecordTask::getCreateTime, programRecordTaskListDto.getCreateTime())
                        .orderByAsc(ProgramRecordTask::getCreateTime)
                        .last("LIMIT " + limit));
        return programRecordTaskList.stream().map(programRecordTask -> {
            ProgramRecordTaskVo programRecordTaskVo = new ProgramRecordTaskVo();
            BeanUtils.copyProperties(programRecordTask, programRecordTaskVo);
            return programRecordTaskVo;
        }).toList();
    }
    
    @Transactional(rollbackFor = Exception.class)
    public Integer updateByCreateTime(ProgramRecordTaskUpdateDto programRecordTaskUpdateDto){
        ProgramRecordTask updateProgramRecordTask = new ProgramRecordTask();
        updateProgramRecordTask.setHandleStatus(programRecordTaskUpdateDto.getAfterHandleStatus());
        return programRecordTaskMapper.update(updateProgramRecordTask,Wrappers.lambdaUpdate(ProgramRecordTask.class)
                        .eq(ProgramRecordTask::getHandleStatus, programRecordTaskUpdateDto.getBeforeHandleStatus())
                        .in(ProgramRecordTask::getCreateTime, programRecordTaskUpdateDto.getCreateTimeSet()));
        
    }
    
    @Transactional(rollbackFor = Exception.class)
    public Integer add(ProgramRecordTaskAddDto orderTicketUserRecordAddDto){
        ProgramRecordTask programRecordTask = new ProgramRecordTask();
        programRecordTask.setId(uidGenerator.getUid());
        programRecordTask.setProgramId(orderTicketUserRecordAddDto.getProgramId());
        programRecordTask.setCreateTime(DateUtils.now());
        programRecordTask.setEditTime(DateUtils.now());
        return programRecordTaskMapper.insert(programRecordTask);
    }
}
