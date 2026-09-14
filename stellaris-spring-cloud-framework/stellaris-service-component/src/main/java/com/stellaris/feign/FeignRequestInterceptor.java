package com.stellaris.feign;

import com.stellaris.threadlocal.BaseParameterHolder;
import com.stellaris.util.StringUtil;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import static com.stellaris.constant.Constant.CODE;
import static com.stellaris.constant.Constant.GRAY_PARAMETER;
import static com.stellaris.constant.Constant.TRACE_ID;
import static com.stellaris.constant.Constant.USER_ID;


/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: feign 参数传递
 * @author: xz_y
 **/

@Slf4j
@AllArgsConstructor
public class FeignRequestInterceptor implements RequestInterceptor {
    
    private final String serverGray;
    
    @Override
    public void apply(RequestTemplate template) {
        try {
            RequestAttributes ra = RequestContextHolder.getRequestAttributes();
            HttpServletRequest request = ra instanceof ServletRequestAttributes sra ? sra.getRequest() : null;

            String traceId = requestParameter(request, TRACE_ID);
            String code = requestParameter(request, CODE);
            String gray = requestParameter(request, GRAY_PARAMETER);
            String userId = requestParameter(request, USER_ID);
            if (StringUtil.isEmpty(gray)) {
                gray = serverGray;
            }
            addHeaderIfPresent(template, TRACE_ID, traceId);
            addHeaderIfPresent(template, CODE, code);
            addHeaderIfPresent(template, GRAY_PARAMETER, gray);
            addHeaderIfPresent(template, USER_ID, userId);
        }catch (Exception e) {
            log.error("FeignRequestInterceptor apply error",e);
        }
    }

    private String requestParameter(HttpServletRequest request, String name) {
        String value = request == null ? null : request.getHeader(name);
        if (StringUtil.isEmpty(value)) {
            // BusinessThreadPool propagates BaseParameterHolder rather than RequestContextHolder.
            // Background consumers and scheduled jobs have neither context and therefore still carry no identity.
            value = BaseParameterHolder.getParameter(name);
        }
        return value;
    }

    private void addHeaderIfPresent(RequestTemplate template, String name, String value) {
        if (StringUtil.isNotEmpty(value)) {
            template.header(name, value);
        }
    }
}
