package com.stellaris.filter;

import com.stellaris.threadlocal.BaseParameterHolder;
import com.stellaris.util.StringUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.skywalking.apm.toolkit.trace.TraceContext;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import static com.stellaris.constant.Constant.SKY_WALKING_TRACE_ID;

public class SkyWalkingFilter extends OncePerRequestFilter {
    
    
    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain) throws ServletException, IOException {
        String skyWalkingTraceId = TraceContext.traceId();
        
        try {
            if (StringUtil.isNotEmpty(skyWalkingTraceId)) {
                BaseParameterHolder.setParameter(SKY_WALKING_TRACE_ID,skyWalkingTraceId);
                MDC.put(SKY_WALKING_TRACE_ID,skyWalkingTraceId);
            }
            filterChain.doFilter(request, response);
        }finally {
            BaseParameterHolder.removeParameter(SKY_WALKING_TRACE_ID);
            MDC.remove(SKY_WALKING_TRACE_ID);
        }
    }
}