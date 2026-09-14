package com.stellaris.exception;

import com.stellaris.common.ApiResponse;
import com.stellaris.enums.BaseCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultExceptionHandlerTest {

    @Test
    void missingRouteReturnsReal404WithoutUsingSystemErrorEnvelope() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/order/list");
        ResponseEntity<ApiResponse<String>> response = new DefaultExceptionHandler().notFoundHandler(
                request, new NoResourceFoundException(HttpMethod.GET, "/order/list"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(BaseCode.NOT_FOUND.getCode());
        assertThat(response.getBody().getMessage()).contains("GET", "/order/list");
    }
}
