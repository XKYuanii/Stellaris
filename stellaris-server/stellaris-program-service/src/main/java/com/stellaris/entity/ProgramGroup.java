package com.stellaris.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stellaris.data.BaseTableData;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 节目分组 实体
 * @author: xz_y
 **/
@Data
@TableName("d_program_group")
public class ProgramGroup extends BaseTableData implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    private Long id;

    /**
     * 节目json
     */
    private String programJson;
    
    /**
     * 最近的节目演出时间
     * */
    private Date recentShowTime;
}
