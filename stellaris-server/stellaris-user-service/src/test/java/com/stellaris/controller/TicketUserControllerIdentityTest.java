package com.stellaris.controller;

import com.stellaris.dto.TicketUserDto;
import com.stellaris.dto.TicketUserIdDto;
import com.stellaris.dto.TicketUserListDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.security.CurrentRequestIdentity;
import com.stellaris.service.TicketUserService;
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
class TicketUserControllerIdentityTest {
    @Mock
    private TicketUserService ticketUserService;
    @Mock
    private CurrentRequestIdentity identity;
    @InjectMocks
    private TicketUserController controller;

    @Test
    void rejectsListingAnotherUsersIdentityDocuments() {
        TicketUserListDto dto = new TicketUserListDto();
        dto.setUserId(7L);
        when(identity.requireUserId()).thenReturn(8L);

        assertThatThrownBy(() -> controller.list(dto))
                .isInstanceOfSatisfying(StellarisFrameException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(BaseCode.PARAMETER_ERROR.getCode()));
        verify(ticketUserService, never()).list(dto, 8L);
    }

    @Test
    void passesCanonicalIdentityWhenAddingTicketUser() {
        TicketUserDto dto = new TicketUserDto();
        dto.setUserId(8L);
        when(identity.requireUserId()).thenReturn(8L);

        controller.add(dto);

        verify(ticketUserService).add(dto, 8L);
    }

    @Test
    void scopesDeletionToCurrentUser() {
        TicketUserIdDto dto = new TicketUserIdDto();
        dto.setId(100L);
        when(identity.requireUserId()).thenReturn(8L);

        controller.delete(dto);

        verify(ticketUserService).delete(dto, 8L);
    }
}
