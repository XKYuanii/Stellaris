package com.stellaris.service.reference;

import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;

/** Signals the recoverable cold-cache condition that asynchronously triggers a rebuild. */
public final class ReferenceInventoryNotReadyException extends StellarisFrameException {
    public ReferenceInventoryNotReadyException(long programId) {
        super(BaseCode.INVENTORY_WARMING_UP.getCode(),
                BaseCode.INVENTORY_WARMING_UP.getMsg() + "，programId=" + programId);
    }
}
