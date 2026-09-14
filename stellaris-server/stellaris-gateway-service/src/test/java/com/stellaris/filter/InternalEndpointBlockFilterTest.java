package com.stellaris.filter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InternalEndpointBlockFilterTest {

    @Test
    void blocksInternalAndMaintenanceRoutes() {
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris/order/order/interior/account/order/count")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris/order/order/interior/reference/inventory/initialize")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris/program/program/interior/reference/reservation/transition")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris/pay/pay/reference/reconciliation/state/batch")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris/order/order/stream/failure/replay")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris/order/order/stream/failure/release")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris/user/user/get/user/ticket/list")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris/user/user/get/mobile")).isTrue();
    }

    @Test
    void blocksInternalRoutesRegardlessOfGatewayRoutePrefix() {
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris-order-service/order/simple/list")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris-order-service/order/stream/failure/replay")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris-order-service/order/stream/failure/release")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris-pay-service/pay/reference/reconciliation/state/batch")).isTrue();
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris-user-service/user/get/mobile")).isTrue();
    }

    @Test
    void allowsUserFacingV5Routes() {
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris/program/program/order/create/v5")).isFalse();
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris/order/order/get")).isFalse();
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris/order/order/pay/check")).isFalse();
        assertThat(InternalEndpointBlockFilter.isBlockedPath(
                "/stellaris-program-service/program/order/create/v5")).isFalse();
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
