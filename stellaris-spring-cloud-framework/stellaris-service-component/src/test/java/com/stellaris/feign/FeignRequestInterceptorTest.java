package com.stellaris.feign;

import com.stellaris.threadlocal.BaseParameterHolder;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static com.stellaris.constant.Constant.CODE;
import static com.stellaris.constant.Constant.GRAY_PARAMETER;
import static com.stellaris.constant.Constant.TRACE_ID;
import static com.stellaris.constant.Constant.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

class FeignRequestInterceptorTest {

    @BeforeEach
    @AfterEach
    void clearContexts() {
        RequestContextHolder.resetRequestAttributes();
        BaseParameterHolder.removeParameterMap();
    }

    @Test
    void propagatesGatewayEstablishedUserIdentityOnSynchronousFeignCalls() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(USER_ID, "42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RequestTemplate template = new RequestTemplate();

        new FeignRequestInterceptor("default").apply(template);

        assertThat(template.headers().get(USER_ID)).containsExactly("42");
    }

    @Test
    void propagatesBusinessThreadPoolContextWithoutServletRequest() {
        BaseParameterHolder.setParameter(TRACE_ID, "trace-1");
        BaseParameterHolder.setParameter(CODE, "code-1");
        BaseParameterHolder.setParameter(GRAY_PARAMETER, "gray-1");
        BaseParameterHolder.setParameter(USER_ID, "42");
        RequestTemplate template = new RequestTemplate();

        new FeignRequestInterceptor("default").apply(template);

        assertThat(template.headers().get(TRACE_ID)).containsExactly("trace-1");
        assertThat(template.headers().get(CODE)).containsExactly("code-1");
        assertThat(template.headers().get(GRAY_PARAMETER)).containsExactly("gray-1");
        assertThat(template.headers().get(USER_ID)).containsExactly("42");
    }

    @Test
    void doesNotAddIdentityWithoutRequestOrThreadLocalContext() {
        RequestTemplate template = new RequestTemplate();

        new FeignRequestInterceptor("default").apply(template);

        assertThat(template.headers()).doesNotContainKey(USER_ID);
    }
}
