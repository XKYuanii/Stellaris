package com.stellaris.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void genericErrorPayloadKeepsTheCallerProvidedCode() {
        ApiResponse<List<String>> response = ApiResponse.error(10054, List.of("field is required"));

        assertThat(response.getCode()).isEqualTo(10054);
        assertThat(response.getData()).containsExactly("field is required");
    }
}
