package com.stellaris.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellaris.entity.AccountProgramPurchase;
import org.apache.ibatis.annotations.Param;

public interface AccountProgramPurchaseMapper extends BaseMapper<AccountProgramPurchase> {
    int initialize(@Param("programId") Long programId, @Param("userId") Long userId);

    int incrementWithinLimit(@Param("programId") Long programId,
                             @Param("userId") Long userId,
                             @Param("amount") int amount,
                             @Param("accountLimit") int accountLimit);

    int decrement(@Param("programId") Long programId,
                  @Param("userId") Long userId,
                  @Param("amount") int amount);

    Integer currentCount(@Param("programId") Long programId,
                         @Param("userId") Long userId);
}
