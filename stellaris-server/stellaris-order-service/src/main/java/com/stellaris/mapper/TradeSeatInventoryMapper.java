package com.stellaris.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stellaris.dto.SeatInventorySnapshotDto;
import com.stellaris.entity.TradeSeatInventory;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface TradeSeatInventoryMapper extends BaseMapper<TradeSeatInventory> {
    int initializeSnapshot(@Param("programId") Long programId,
                           @Param("saleVersion") String saleVersion,
                           @Param("seats") List<SeatInventorySnapshotDto> seats);

    /**
     * 整单一次性 CAS 锁座。CAS 条件与逐座版本完全一致：票档、价格、销售版本、
     * 状态、归属和逻辑删除全部校验，只是把 N 次往返合成一条语句。
     *
     * @return 实际更新的座位数。调用方必须以「等于本单座位数」作为整单成功条件，
     *         少一行即表示有座位被抢、票档或价格已变、或销售版本过期，须整单回滚。
     */
    int lockSeats(@Param("programId") Long programId,
                  @Param("saleVersion") String saleVersion,
                  @Param("reservationId") String reservationId,
                  @Param("orderNumber") Long orderNumber,
                  @Param("seats") List<SeatInventorySnapshotDto> seats);

    int transitionOrderSeats(@Param("programId") Long programId,
                             @Param("orderNumber") Long orderNumber,
                             @Param("reservationId") String reservationId,
                             @Param("sourceStatus") Integer sourceStatus,
                             @Param("targetStatus") Integer targetStatus);

    long countAvailable(@Param("programId") Long programId,
                        @Param("ticketCategoryId") Long ticketCategoryId);

    long countProgram(@Param("programId") Long programId);

    long countDifferentSaleVersion(@Param("programId") Long programId,
                                   @Param("saleVersion") String saleVersion);

    List<SeatInventorySnapshotDto> currentSnapshot(@Param("programId") Long programId);
}
