package com.stellaris.service.reference;

/**
 * The payment and order facts were both read successfully, but cannot be reconciled automatically.
 * Repeating the same comparison cannot repair the data, so the event must stop retrying and alert.
 */
public class PaymentReconciliationConflictException extends RuntimeException {
    private final String code;

    public PaymentReconciliationConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
