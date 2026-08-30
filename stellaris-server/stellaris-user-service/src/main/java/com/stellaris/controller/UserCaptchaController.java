package com.stellaris.controller;

import com.stellaris.captcha.model.common.ResponseModel;
import com.stellaris.captcha.model.vo.CaptchaVO;
import com.stellaris.common.ApiResponse;
import com.stellaris.service.UserCaptchaService;
import com.stellaris.vo.CheckNeedCaptchaDataVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 验证码 控制层
 * @author: xz_y
 **/
@RestController
@RequestMapping("/user/captcha")
@Tag(name = "captcha", description = "验证码")
public class UserCaptchaController {
    
    @Autowired
    private UserCaptchaService userCaptchaService;
    
    @Operation(summary  = "检查是否需要验证码")
    @PostMapping(value = "/check/need")
    public ApiResponse<CheckNeedCaptchaDataVo> checkNeedCaptcha(){
        return ApiResponse.ok(userCaptchaService.checkNeedCaptcha());
    }
    
    @Operation(summary  = "获取验证码")
    @PostMapping(value = "/get")
    public ResponseModel getCaptcha(@RequestBody CaptchaVO captchaVO){
        return userCaptchaService.getCaptcha(captchaVO);
    }
    
    @Operation(summary  = "验证验证码")
    @PostMapping(value = "/verify")
    public ResponseModel verifyCaptcha(@RequestBody CaptchaVO captchaVO){
        return userCaptchaService.verifyCaptcha(captchaVO);
    }
}
