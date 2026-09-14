package com.stellaris.service.reference;

/**
 * The payment workflow is healthy but has not reached a terminal fact yet. This state must remain
 * retryable without being counted as an infrastructure failure or triggering a failure-age alert.
 */
public class PaymentReconciliationPendingException extends RuntimeException {
    public PaymentReconciliationPendingException(String message) {
        super(message);
    }
}
