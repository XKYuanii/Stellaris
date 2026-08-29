package com.stellaris.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stellaris.data.BaseTableData;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** Kafka DLT 的持久审计事实，支持保留原 eventId 的人工重放。 */
@Data
@TableName("d_order_create_dlt_record")
public class OrderCreateDltRecord extends BaseTableData implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private Long id;
    private Long eventId;
    private Long orderNumber;
    private Long userId;
    private Long programId;
    private String dltTopic;
    private Integer sourcePartition;
    private Long sourceOffset;
    private String payload;
    private String exceptionMessage;
    /** RECORDED/RAW_ONLY/REPLAYED。 */
    private String recordStatus;
    private Integer replayCount;
}
