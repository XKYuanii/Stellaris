package com.stellaris.security;

import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.threadlocal.BaseParameterHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static com.stellaris.constant.Constant.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentRequestIdentityTest {
    private final CurrentRequestIdentity identity = new CurrentRequestIdentity();

    @AfterEach
    void cleanUp() {
        BaseParameterHolder.removeParameterMap();
    }

    @Test
    void returnsGatewayEstablishedUserId() {
        BaseParameterHolder.setParameter(USER_ID, "42");

        assertThat(identity.requireUserId()).isEqualTo(42L);
    }

    @Test
    void rejectsMissingOrMalformedIdentity() {
        assertThatThrownBy(identity::requireUserId)
                .isInstanceOfSatisfying(StellarisFrameException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(BaseCode.USER_NOT_LOGIN.getCode()));

        BaseParameterHolder.setParameter(USER_ID, "not-a-user");
        assertThatThrownBy(identity::requireUserId)
                .isInstanceOfSatisfying(StellarisFrameException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(BaseCode.USER_NOT_LOGIN.getCode()));
    }
}
