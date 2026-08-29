package com.stellaris.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stellaris.data.BaseTableData;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目对账记录任务 实体
 * @author: 阿星不是程序员
 **/
@Data
@TableName("d_program_record_task")
public class ProgramRecordTask extends BaseTableData implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    private Long id;
    
    /**
     * 节目表id
     */
    private Long programId;

    /**
     * 处理状态 1:未处理 1:已处理
     */
    private Integer handleStatus;
    
    
}
