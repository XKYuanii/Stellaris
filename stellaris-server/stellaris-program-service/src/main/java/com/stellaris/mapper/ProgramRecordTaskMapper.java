package com.stellaris.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellaris.entity.ProgramRecordTask;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目对账记录任务 mapper
 * @author: 阿星不是程序员
 **/
public interface ProgramRecordTaskMapper extends BaseMapper<ProgramRecordTask> {
    /**
     * 真实删除节目对账记录任务数据
     * @return 结果
     * */
    Integer relDelProgramRecordTask();
}
