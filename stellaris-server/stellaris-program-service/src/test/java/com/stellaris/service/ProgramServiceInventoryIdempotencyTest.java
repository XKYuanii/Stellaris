package com.stellaris.service;

import com.stellaris.dto.ReduceRemainNumberDto;
import com.stellaris.entity.OrderInventoryOperation;
import com.stellaris.mapper.OrderInventoryOperationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProgramServiceInventoryIdempotencyTest {

    @Test
    void returnsSuccessWithoutUpdatingInventoryWhenOrderWasAlreadyHandled() {
        OrderInventoryOperationMapper operationMapper = mock(OrderInventoryOperationMapper.class);
        OrderInventoryOperation operation = new OrderInventoryOperation();
        operation.setEventId(33L);
        when(operationMapper.selectOne(any())).thenReturn(operation);
        ProgramService programService = new ProgramService();
        ReflectionTestUtils.setField(programService, "orderInventoryOperationMapper", operationMapper);

        ReduceRemainNumberDto dto = new ReduceRemainNumberDto();
        dto.setProgramId(11L);
        dto.setOrderNumber(22L);
        dto.setEventId(33L);
        dto.setIntentId("reservation-1");
        dto.setReservationExpireTime(new Date(System.currentTimeMillis() - 60_000));

        assertTrue(programService.operateSeatLockAndTicketCategoryRemainNumber(dto));
        verify(operationMapper).selectOne(any());
    }

    @Test
    void rejectsSameOrderNumberWithDifferentEventId() {
        OrderInventoryOperationMapper operationMapper = mock(OrderInventoryOperationMapper.class);
        OrderInventoryOperation operation = new OrderInventoryOperation();
        operation.setEventId(33L);
        when(operationMapper.selectOne(any())).thenReturn(operation);
        ProgramService programService = new ProgramService();
        ReflectionTestUtils.setField(programService, "orderInventoryOperationMapper", operationMapper);

        ReduceRemainNumberDto dto = new ReduceRemainNumberDto();
        dto.setProgramId(11L);
        dto.setOrderNumber(22L);
        dto.setEventId(44L);

        assertThatThrownBy(() -> programService.operateSeatLockAndTicketCategoryRemainNumber(dto))
                .isInstanceOf(RuntimeException.class);
    }
}
