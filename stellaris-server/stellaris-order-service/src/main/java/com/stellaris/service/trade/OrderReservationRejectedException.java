package com.stellaris.service.trade;

/** 可以形成稳定业务拒绝结果的创建订单异常。 */
public class OrderReservationRejectedException extends RuntimeException {
    private final String rejectCode;

    public OrderReservationRejectedException(String rejectCode) {
        super(rejectCode);
        this.rejectCode = rejectCode;
    }

    public String getRejectCode() {
        return rejectCode;
    }
}
