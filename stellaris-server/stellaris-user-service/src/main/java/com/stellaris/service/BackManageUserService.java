package com.stellaris.service;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.fastjson2.JSON;
import com.stellaris.dto.BackManageLoginDto;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.properties.BackManageProperties;
import com.stellaris.vo.BackManageLoginVo;
import com.stellaris.vo.BackManageUserDetailVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 后台管理登录 service
 * @author: 阿星不是程序员
 **/
@Service
public class BackManageUserService {
    
    @Autowired
    private BackManageProperties backManageProperties;
    

    public BackManageLoginVo login(BackManageLoginDto backManageLoginDto){
        //验证用户信息
        verifyUser(backManageLoginDto);
        //登录
        StpUtil.login(backManageLoginDto.getUsername());
        SaSession session = StpUtil.getSession();
        BackManageUserDetailVo backManageUserDetailVo = new BackManageUserDetailVo();
        backManageUserDetailVo.setUserId("1");
        backManageUserDetailVo.setHomePath("/");
        backManageUserDetailVo.setRealName("阿星不是程序员");
        backManageUserDetailVo.setDesc("javaup@yeah.net");
        backManageUserDetailVo.setUsername(backManageLoginDto.getUsername());
        backManageUserDetailVo.setAvatar("https://multimedia-javaup.cn/%E5%A4%A7%E9%BA%A6pro/dog.png");
        session.set("userDetail", JSON.toJSONString(backManageUserDetailVo));
        BackManageLoginVo backManageLoginVo = new BackManageLoginVo();
        backManageLoginVo.setId("1");
        backManageLoginVo.setRealName("阿星不是程序员");
        backManageLoginVo.setUsername(backManageLoginDto.getUsername());
        backManageLoginVo.setPassword(backManageLoginDto.getPassword());
        backManageLoginVo.setAccessToken(StpUtil.getTokenValue());
        return backManageLoginVo;
    }
    
    public BackManageUserDetailVo userInfo() {
        Object userDetailObj = StpUtil.getSession().get("userDetail");
        if (userDetailObj == null) {
            throw new StellarisFrameException("用户未登录或登录已过期");
        }
        return JSON.parseObject(String.valueOf(userDetailObj), BackManageUserDetailVo.class);
    }
    
    public void verifyUser(BackManageLoginDto backManageLoginDto){
        if (!backManageProperties.getUsername().equals(backManageLoginDto.getUsername())) {
            throw new StellarisFrameException("用户名错误");
        }
        if (!backManageProperties.getPassword().equals(backManageLoginDto.getPassword())) {
            throw new StellarisFrameException("用户密码错误");
        }
    }
}
