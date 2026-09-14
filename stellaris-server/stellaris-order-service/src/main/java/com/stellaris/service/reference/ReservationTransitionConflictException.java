package com.stellaris.service.reference;

/**
 * Redis executed the transition Lua successfully, but the stored reservation state cannot accept
 * the requested terminal transition. Retrying the same command cannot repair this conflict, so the
 * relay must stop automatic retries and surface it for operator reconciliation.
 */
public class ReservationTransitionConflictException extends RuntimeException {
    private final String code;

    public ReservationTransitionConflictException(String code, String intentId) {
        super("reservation transition requires manual reconciliation, code=" + code
                + ", intentId=" + intentId);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
