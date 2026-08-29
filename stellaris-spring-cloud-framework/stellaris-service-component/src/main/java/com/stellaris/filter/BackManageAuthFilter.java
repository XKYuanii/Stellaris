package com.stellaris.filter;

import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.stellaris.common.ApiResponse;
import com.stellaris.enums.BaseCode;
import com.stellaris.properties.BackManageProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * @program: 数据中台实战项目。 添加 阿星不是程序员 微信，添加时备注 中台 来获取项目的完整资料 
 * @description: 登录状态过滤器
 * @author: 阿星不是程序员
 **/
@WebFilter(value = "/*", filterName = "backManageAuthFilter")
public class BackManageAuthFilter extends OncePerRequestFilter {
    
    private String trueStr = "true";
    
    private final BackManageProperties backManageProperties;
    
    public BackManageAuthFilter(BackManageProperties backManageProperties) {
        this.backManageProperties = backManageProperties;
    }
    
    @Override
    protected void doFilterInternal(@NonNull final HttpServletRequest request, 
                                    @NonNull final HttpServletResponse response,
                                    @NonNull final FilterChain filterChain) throws ServletException, IOException {
        String backManage = request.getHeader("back_manage");
        if (!trueStr.equals(backManage)) {
            filterChain.doFilter(request, response);
            return;
        }
        boolean pass = false;
        String requestUri = request.getRequestURI();
        //如果是登录请求，则可以放行
        if (backManageProperties.getLoginExcludeApi().contains(requestUri)) {
            pass = true;
        }else {
            //检查登录状态
            if (StpUtil.isLogin()){
                //用户已登录，放行
                pass = true;
            }
        }
        if (pass) {
            //放行
            filterChain.doFilter(request, response);
        }else {
            //用户未登录，返回错误码
            response.setCharacterEncoding("UTF-8");
            response.setContentType("text/html; charset=utf-8");
            try (PrintWriter writer = response.getWriter()) {
                JSONObject jsonObject = new JSONObject();
                writer.print(JSON.toJSONString(ApiResponse.error(BaseCode.USER_NOT_LOGIN)));
            }
        }
    }
}
