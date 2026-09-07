package com.stellaris.security;

import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.threadlocal.BaseParameterHolder;
import org.springframework.stereotype.Component;

import static com.stellaris.constant.Constant.USER_ID;

/**
 * Reads the identity established by the gateway and propagated by BaseParameterFilter.
 * Request-body user ids are business input and must never be treated as authentication.
 */
@Component
public class CurrentRequestIdentity {

    public Long requireUserId() {
        String value = BaseParameterHolder.getParameter(USER_ID);
        if (value == null || value.isBlank()) {
            throw new StellarisFrameException(BaseCode.USER_NOT_LOGIN);
        }
        try {
            long userId = Long.parseLong(value);
            if (userId <= 0) {
                throw new NumberFormatException("user id must be positive");
            }
            return userId;
        } catch (NumberFormatException ignored) {
            throw new StellarisFrameException(BaseCode.USER_NOT_LOGIN);
        }
    }
}
