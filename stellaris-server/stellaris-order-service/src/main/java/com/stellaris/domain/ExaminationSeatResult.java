package com.stellaris.domain;

import com.stellaris.entity.OrderTicketUserRecord;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: redis和数据对账结果(座位维度) - 以数据库为准
 * @author: xz_y
 **/
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExaminationSeatResult {
    
    /**
     * Redis和数据库匹配的座位数量
     * */
    private int matchCount;

    /**
     * 需要向redis中补充的座位（数据库有但Redis没有）
     * */
    private List<OrderTicketUserRecord> needToRedisSeatRecordList;
}
