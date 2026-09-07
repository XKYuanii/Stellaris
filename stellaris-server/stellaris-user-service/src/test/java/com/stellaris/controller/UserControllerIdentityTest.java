package com.stellaris.controller;

import com.stellaris.dto.UserIdDto;
import com.stellaris.dto.UserUpdateDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.security.CurrentRequestIdentity;
import com.stellaris.service.UserService;
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
class UserControllerIdentityTest {
    @Mock
    private UserService userService;
    @Mock
    private CurrentRequestIdentity identity;
    @InjectMocks
    private UserController controller;

    @Test
    void rejectsReadingAnotherUsersProfile() {
        UserIdDto dto = new UserIdDto();
        dto.setId(7L);
        when(identity.requireUserId()).thenReturn(8L);

        assertThatThrownBy(() -> controller.getById(dto))
                .isInstanceOfSatisfying(StellarisFrameException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(BaseCode.PARAMETER_ERROR.getCode()));
        verify(userService, never()).getById(dto);
    }

    @Test
    void permitsUpdatingOnlyTheCurrentProfile() {
        UserUpdateDto dto = new UserUpdateDto();
        dto.setId(8L);
        when(identity.requireUserId()).thenReturn(8L);

        controller.update(dto);

        verify(userService).update(dto);
    }
}
