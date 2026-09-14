package com.stellaris.service;

import com.stellaris.dto.OrderMaterializationQueryDto;
import com.stellaris.entity.OrderRequest;
import com.stellaris.entity.OrderStreamFailure;
import com.stellaris.mapper.OrderRequestMapper;
import com.stellaris.mapper.OrderStreamFailureMapper;
import com.stellaris.vo.OrderMaterializationVo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderMaterializationServiceTest {

    @Test
    void auditedPoisonMessageIsTerminalRejected() {
        OrderRequestMapper requestMapper = mock(OrderRequestMapper.class);
        OrderStreamFailureMapper failureMapper = mock(OrderStreamFailureMapper.class);
        OrderService service = service(requestMapper, failureMapper);
        OrderStreamFailure failure = new OrderStreamFailure();
        failure.setOrderNumber(1001L);
        failure.setUserId(9L);
        failure.setRecordStatus("RECORDED");
        when(failureMapper.selectOne(any())).thenReturn(failure);

        OrderMaterializationVo result = service.materialization(query(1001L), 9L);

        assertThat(result.getStatus()).isEqualTo("REJECTED");
        assertThat(result.getRejectCode()).isEqualTo("ORDER_EVENT_AUDITED");
    }

    @Test
    void durableRequestResultTakesPrecedenceOverAuditRecord() {
        OrderRequestMapper requestMapper = mock(OrderRequestMapper.class);
        OrderStreamFailureMapper failureMapper = mock(OrderStreamFailureMapper.class);
        OrderService service = service(requestMapper, failureMapper);
        OrderRequest request = new OrderRequest();
        request.setResultStatus("CREATED");
        when(requestMapper.selectOne(any())).thenReturn(request);

        assertThat(service.materialization(query(1002L), 9L).getStatus()).isEqualTo("CREATED");
    }

    private OrderService service(OrderRequestMapper requestMapper, OrderStreamFailureMapper failureMapper) {
        OrderService service = new OrderService();
        ReflectionTestUtils.setField(service, "orderRequestMapper", requestMapper);
        ReflectionTestUtils.setField(service, "orderStreamFailureMapper", failureMapper);
        return service;
    }

    private OrderMaterializationQueryDto query(long orderNumber) {
        OrderMaterializationQueryDto query = new OrderMaterializationQueryDto();
        query.setOrderNumber(orderNumber);
        return query;
    }
}
