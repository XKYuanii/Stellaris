package com.stellaris.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/** 数据库权威的账号场次限购计数。 */
@Data
@TableName("t_account_program_purchase")
public class AccountProgramPurchase {
    private Long programId;
    private Long userId;
    private Integer purchaseCount;
    private Date updatedAt;
}
