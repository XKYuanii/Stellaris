package com.stellaris.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.stellaris.data.BaseTableData;
import lombok.Data;

/** 无法自动处理的 Redis Stream 消息审计。 */
@Data
@TableName("d_order_stream_failure")
public class OrderStreamFailure extends BaseTableData {
    private Long id;
    private String streamKey;
    private String streamId;
    private Long eventId;
    private Long orderNumber;
    private Long userId;
    private Long programId;
    /** Immutable Redis reservation identity used for safe replay/release even if payload JSON is malformed. */
    private String intentId;
    private String payload;
    private String exceptionMessage;
    private String recordStatus;
    private Integer replayCount;
}
