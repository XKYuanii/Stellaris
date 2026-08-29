package com.stellaris.client;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.TicketUserListDto;
import com.stellaris.dto.UserGetAndTicketUserListDto;
import com.stellaris.dto.UserIdDto;
import com.stellaris.enums.BaseCode;
import com.stellaris.vo.UserGetAndTicketUserListVo;
import com.stellaris.vo.TicketUserVo;
import com.stellaris.vo.UserVo;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 用户服务 feign 异常
 * @author: 阿星不是程序员
 **/
@Component
public class UserClientFallback implements UserClient {
    
    @Override
    public ApiResponse<UserVo> getById(final UserIdDto userIdDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
    
    @Override
    public ApiResponse<List<TicketUserVo>> list(final TicketUserListDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
    
    @Override
    public ApiResponse<UserGetAndTicketUserListVo> getUserAndTicketUserList(final UserGetAndTicketUserListDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
}
