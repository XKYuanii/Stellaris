package com.stellaris.filter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InternalEndpointBlockFilterTest {

    @Test
    void blocksInternalAndMaintenanceRoutes() {
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris/order/order/account/order/count")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris/order/order/reference/reconciliation/state/batch")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris/program/program/reference/reconciliation/run")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris/order/order/create/dlt/replay")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris/user/user/get/user/ticket/list")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris/user/user/get/mobile")).isTrue();
    }

    @Test
    void allowsUserFacingV5Routes() {
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris/program/program/order/create/v5")).isFalse();
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris/order/order/get")).isFalse();
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris/order/order/pay/check")).isFalse();
    }

    @Test
    void acceptsOnlyPositiveDemoIdentityHeaderValues() {
        assertThat(RequestValidationFilter.positiveLong("42"))
                .isEqualTo("42");
        assertThat(RequestValidationFilter.positiveLong("0")).isNull();
        assertThat(RequestValidationFilter.positiveLong("not-a-user")).isNull();
        assertThat(RequestValidationFilter.positiveLong(null)).isNull();
    }
}
