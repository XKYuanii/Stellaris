package com.stellaris.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellaris.entity.OrderRequest;
import org.apache.ibatis.annotations.Param;

public interface OrderRequestMapper extends BaseMapper<OrderRequest> {
    int acquire(@Param("reservationId") String reservationId,
                @Param("programId") Long programId,
                @Param("userId") Long userId,
                @Param("requestId") String requestId,
                @Param("orderNumber") Long orderNumber,
                @Param("requestFingerprint") String requestFingerprint);

    int markCreated(@Param("reservationId") String reservationId);

    int recordRejected(@Param("reservationId") String reservationId,
                       @Param("programId") Long programId,
                       @Param("userId") Long userId,
                       @Param("requestId") String requestId,
                       @Param("orderNumber") Long orderNumber,
                       @Param("requestFingerprint") String requestFingerprint,
                       @Param("rejectCode") String rejectCode);
}
