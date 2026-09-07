package com.stellaris.controller;

import com.stellaris.dto.ProgramOrderCreateDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.security.CurrentRequestIdentity;
import com.stellaris.service.strategy.impl.ProgramOrderV5Strategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProgramOrderControllerIdentityTest {
    @Mock
    private ProgramOrderV5Strategy strategy;
    @Mock
    private CurrentRequestIdentity identity;
    @InjectMocks
    private ProgramOrderController controller;

    @Test
    void rejectsBodyUserThatDiffersFromAuthenticatedUser() {
        ProgramOrderCreateDto dto = new ProgramOrderCreateDto();
        dto.setUserId(7L);
        when(identity.requireUserId()).thenReturn(8L);

        assertThatThrownBy(() -> controller.createV5(dto))
                .isInstanceOfSatisfying(StellarisFrameException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(BaseCode.PARAMETER_ERROR.getCode()));
        verify(strategy, never()).createOrder(dto);
    }

    @Test
    void passesCanonicalUserToV5Strategy() {
        ProgramOrderCreateDto dto = new ProgramOrderCreateDto();
        dto.setUserId(8L);
        when(identity.requireUserId()).thenReturn(8L);
        when(strategy.createOrder(dto)).thenReturn("10001");

        assertThat(controller.createV5(dto).getData()).isEqualTo("10001");
        assertThat(dto.getUserId()).isEqualTo(8L);
        verify(strategy).createOrder(dto);
    }
}
