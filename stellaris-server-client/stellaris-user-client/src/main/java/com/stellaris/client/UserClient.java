package com.stellaris.client;

import com.stellaris.common.ApiResponse;
import com.stellaris.dto.TicketUserListDto;
import com.stellaris.dto.UserGetAndTicketUserListDto;
import com.stellaris.dto.UserIdDto;
import com.stellaris.vo.TicketUserVo;
import com.stellaris.vo.UserGetAndTicketUserListVo;
import com.stellaris.vo.UserVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;

import static com.stellaris.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;
import static com.stellaris.constant.Constant.USER_ID;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 用户服务 feign
 * @author: xz_y
 **/
@Component
@FeignClient(value = SPRING_INJECT_PREFIX_DISTINCTION_NAME+"-"+"user-service",fallback = UserClientFallback.class)
public interface UserClient {
    
    /**
     * 查询用户(通过id)
     * @param dto 参数
     * @return 结果
     * */
    @PostMapping(value = "/user/getById")
    ApiResponse<UserVo> getById(UserIdDto dto);
    

    /**
     * 查询购票人(通过userId)
     * @param authenticatedUserId 网关已经确认的当前用户；显式传递以支持异步线程中的 Feign 回源
     * @param dto 参数
     * @return 结果
     * */
    @PostMapping(value = "/ticket/user/list")
    ApiResponse<List<TicketUserVo>> list(@RequestHeader(USER_ID) String authenticatedUserId,
                                         TicketUserListDto dto);
    
    /**
     * 查询用户和购票人集合
     * @param dto 参数
     * @return 结果
     */
    @PostMapping(value = "/user/get/user/ticket/list")
    ApiResponse<UserGetAndTicketUserListVo> getUserAndTicketUserList(UserGetAndTicketUserListDto dto);
    
}
