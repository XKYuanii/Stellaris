package com.stellaris.filter;


import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSON;
import com.baidu.fsg.uid.UidGenerator;
import com.stellaris.conf.RequestTemporaryWrapper;
import com.stellaris.enums.BaseCode;
import com.stellaris.exception.ArgumentError;
import com.stellaris.exception.ArgumentException;
import com.stellaris.exception.StellarisFrameException;
import com.stellaris.property.GatewayProperty;
import com.stellaris.service.ChannelDataService;
import com.stellaris.service.TokenService;
import com.stellaris.threadlocal.BaseParameterHolder;
import com.stellaris.util.RsaSignTool;
import com.stellaris.util.RsaTool;
import com.stellaris.util.StringUtil;
import com.stellaris.vo.GetChannelDataVo;
import com.stellaris.vo.UserVo;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static com.stellaris.constant.Constant.GRAY_PARAMETER;
import static com.stellaris.constant.Constant.TRACE_ID;
import static com.stellaris.constant.GatewayConstant.BUSINESS_BODY;
import static com.stellaris.constant.GatewayConstant.CODE;
import static com.stellaris.constant.GatewayConstant.DEMO_USER_ID;
import static com.stellaris.constant.GatewayConstant.ENCRYPT;
import static com.stellaris.constant.GatewayConstant.NO_VERIFY;
import static com.stellaris.constant.GatewayConstant.PROGRAM_ID_HEADER;
import static com.stellaris.constant.GatewayConstant.REQUEST_BODY;
import static com.stellaris.constant.GatewayConstant.TOKEN;
import static com.stellaris.constant.GatewayConstant.USER_ID;
import static com.stellaris.constant.GatewayConstant.V2;
import static com.stellaris.constant.GatewayConstant.VERIFY_VALUE;

/**
 * @program: Stellaris（星演）高并发票务平台。
 * @description: 请求过滤器
 * @author: xz_y
 **/

@Component
@Slf4j
public class RequestValidationFilter implements GlobalFilter, Ordered {

    @Autowired
    private ChannelDataService channelDataService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private GatewayProperty gatewayProperty;
    
    @Autowired
    private UidGenerator uidGenerator;
    
    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        return doFilter(exchange, chain);
    }
    
    public Mono<Void> doFilter(final ServerWebExchange exchange, final GatewayFilterChain chain){
        ServerHttpRequest request = exchange.getRequest();
        String traceId = request.getHeaders().getFirst(TRACE_ID);
        String gray = request.getHeaders().getFirst(GRAY_PARAMETER);
        String noVerify = request.getHeaders().getFirst(NO_VERIFY);
        if (StringUtil.isEmpty(traceId)) {
            traceId = String.valueOf(uidGenerator.getUid());
        }
        MDC.put(TRACE_ID,traceId);
        Map<String,String> headMap = new HashMap<>(8);
        headMap.put(TRACE_ID,traceId);
        headMap.put(GRAY_PARAMETER,gray);
        if (StringUtil.isNotEmpty(noVerify)) {
            headMap.put(NO_VERIFY,noVerify);
        }
        BaseParameterHolder.setParameter(TRACE_ID,traceId);
        BaseParameterHolder.setParameter(GRAY_PARAMETER,gray);
        MediaType contentType = request.getHeaders().getContentType();
        //application json请求
        if (Objects.nonNull(contentType) && contentType.toString().toLowerCase().contains(MediaType.APPLICATION_JSON_VALUE.toLowerCase())) {
            return readBody(exchange,chain,headMap);
        }else {
            Map<String, String> map = doExecute("", exchange);
            map.remove(REQUEST_BODY);
            map.putAll(headMap);
            ServerHttpRequest sanitized = request.mutate().headers(httpHeaders -> {
                // userId/code 只能由本次网关校验结果产生，不能沿用客户端自带值。
                httpHeaders.remove(USER_ID);
                httpHeaders.remove(DEMO_USER_ID);
                httpHeaders.remove(CODE);
                httpHeaders.remove(PROGRAM_ID_HEADER);
                map.forEach(httpHeaders::set);
            }).build();
            return chain.filter(exchange.mutate().request(sanitized).build());
        }
    } 

    private Mono<Void> readBody(ServerWebExchange exchange, GatewayFilterChain chain, Map<String,String> headMap){
        log.info("current thread readBody : {}",Thread.currentThread().getName());
        RequestTemporaryWrapper requestTemporaryWrapper = new RequestTemporaryWrapper();

        // 不让 StringDecoder/BodyInserter 根据运行机器默认字符集往返转换 JSON。
        // 浏览器 JSON 协议统一按 UTF-8 解码和编码，避免中文姓名在网关重写请求体后变成乱码。
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    return new String(bytes, StandardCharsets.UTF_8);
                })
                .defaultIfEmpty("")
                .map(originalBody -> execute(requestTemporaryWrapper, originalBody, exchange))
                .flatMap(modifiedBody -> {
                    byte[] bodyBytes = modifiedBody.getBytes(StandardCharsets.UTF_8);
                    ServerHttpRequest request = decorateHead(exchange, bodyBytes,
                            requestTemporaryWrapper, headMap);
                    return chain.filter(exchange.mutate().request(request).build());
                })
                .onErrorResume((Function<Throwable, Mono<Void>>) Mono::error);
    }
    
    public String execute(RequestTemporaryWrapper requestTemporaryWrapper,String requestBody,ServerWebExchange exchange){
        //进行业务验证，并将相关参数放入map
        Map<String, String> map = doExecute(requestBody, exchange);
        String body = map.get(REQUEST_BODY);
        map.remove(REQUEST_BODY);
        requestTemporaryWrapper.setMap(map);
        return body;
    }

    private Map<String,String> doExecute(String originalBody,ServerWebExchange exchange){
        ServerHttpRequest request = exchange.getRequest();
        String requestBody = originalBody;
        Map<String, String> bodyContent = new HashMap<>(32);
        if (StringUtil.isNotEmpty(originalBody)) {
            bodyContent = JSON.parseObject(originalBody, Map.class);
        }
        String code = null;
        String token;   
        String userId = null;
        String url = request.getPath().value();
        String noVerify = request.getHeaders().getFirst(NO_VERIFY);
        boolean allowNormalAccess = gatewayProperty.isAllowNormalAccess();
        if ((!allowNormalAccess) && (VERIFY_VALUE.equals(noVerify))) {
            throw new StellarisFrameException(BaseCode.ONLY_SIGNATURE_ACCESS_IS_ALLOWED);
        }
        if (checkParameter(originalBody,noVerify) && !skipCheckParameter(url)) {

            String encrypt = request.getHeaders().getFirst(ENCRYPT);
            //应用渠道
            code = bodyContent.get(CODE);
            //token
            token = request.getHeaders().getFirst(TOKEN);
            
            GetChannelDataVo channelDataVo = channelDataService.getChannelDataByCode(code);
            
            if (StringUtil.isNotEmpty(encrypt) && V2.equals(encrypt)) {
                String decrypt = RsaTool.decrypt(bodyContent.get(BUSINESS_BODY),channelDataVo.getDataSecretKey());
                bodyContent.put(BUSINESS_BODY,decrypt);
            }
            boolean checkFlag = RsaSignTool.verifyRsaSign256(bodyContent, channelDataVo.getSignPublicKey());
            if (!checkFlag) {
                throw new StellarisFrameException(BaseCode.RSA_SIGN_ERROR);
            }

            boolean skipCheckTokenResult = skipCheckToken(url);
            if (!skipCheckTokenResult && StringUtil.isEmpty(token)) {
                ArgumentError argumentError = new ArgumentError();
                argumentError.setArgumentName(token);
                argumentError.setMessage("token参数为空");
                List<ArgumentError> argumentErrorList = new ArrayList<>();
                argumentErrorList.add(argumentError);
                throw new ArgumentException(BaseCode.ARGUMENT_EMPTY.getCode(),argumentErrorList);
            }

            if (!skipCheckTokenResult) {
                UserVo userVo = tokenService.getUser(token,code,channelDataVo.getTokenSecret());
                userId = userVo.getId();
            }
            
            if (StringUtil.isEmpty(userId) && checkNeedUserId(url) && StringUtil.isNotEmpty(token)) {
                UserVo userVo = tokenService.getUser(token,code,channelDataVo.getTokenSecret());
                userId = userVo.getId();
            }
            
            requestBody = bodyContent.get(BUSINESS_BODY);
        } else if (allowNormalAccess && VERIFY_VALUE.equals(noVerify)) {
            // This explicit header exists only for isolated local demos. Consume it at
            // the Gateway and rebuild the canonical downstream userId header.
            userId = positiveLong(request.getHeaders().getFirst(DEMO_USER_ID));
        }
        Map<String,String> map = new HashMap<>(4);
        map.put(REQUEST_BODY,requestBody);
        if (StringUtil.isNotEmpty(code)) {
            map.put(CODE,code);
        }
        if (StringUtil.isNotEmpty(userId)) {
            map.put(USER_ID,userId);
        }
        String programId = extractPositiveLong(requestBody, "programId");
        if (StringUtil.isNotEmpty(programId)) {
            map.put(PROGRAM_ID_HEADER, programId);
        }
        return map;
    }

    static String extractPositiveLong(String body, String field) {
        if (StringUtil.isEmpty(body)) {
            return null;
        }
        try {
            Object value = JSON.parseObject(body).get(field);
            long parsed = value == null ? -1 : Long.parseLong(String.valueOf(value));
            return parsed > 0 ? String.valueOf(parsed) : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static String positiveLong(String value) {
        try {
            long parsed = value == null ? -1 : Long.parseLong(value);
            return parsed > 0 ? String.valueOf(parsed) : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
    /**
     * 将网关层request请求头中的重要参数传递给后续的微服务中
     */
    private ServerHttpRequestDecorator decorateHead(ServerWebExchange exchange, byte[] bodyBytes,
                                                      RequestTemporaryWrapper requestTemporaryWrapper,
                                                      Map<String,String> headMap){
        return new ServerHttpRequestDecorator(exchange.getRequest()){
            @Override
            public HttpHeaders getHeaders() {
                log.info("current thread getHeaders: {}",Thread.currentThread().getName());
                HttpHeaders newHeaders = new HttpHeaders();
                newHeaders.putAll(exchange.getRequest().getHeaders());
                newHeaders.remove(USER_ID);
                newHeaders.remove(DEMO_USER_ID);
                newHeaders.remove(CODE);
                newHeaders.remove(PROGRAM_ID_HEADER);
                newHeaders.remove(HttpHeaders.TRANSFER_ENCODING);
                Map<String, String> map = requestTemporaryWrapper.getMap();
                if (CollectionUtil.isNotEmpty(map)) {
                    newHeaders.setAll(map);
                }
                if (CollectionUtil.isNotEmpty(headMap)) {
                    newHeaders.setAll(headMap);
                }
                newHeaders.setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
                newHeaders.setContentLength(bodyBytes.length);
                if (CollectionUtil.isNotEmpty(headMap) && StringUtil.isNotEmpty(headMap.get(TRACE_ID))) {
                    MDC.put(TRACE_ID,headMap.get(TRACE_ID));
                }
                return newHeaders;
            }

            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.just(exchange.getResponse().bufferFactory().wrap(bodyBytes));
            }
        };
    }

    @Override
    public int getOrder() {
        return -2;
    }

    public boolean skipCheckToken(String url){
        for (String skipCheckTokenPath : gatewayProperty.getCheckTokenPaths()) {
            PathMatcher matcher = new AntPathMatcher();
            if (matcher.match(skipCheckTokenPath, url)) {
                return false;
            }
        }
        return true;
    }
    
    public boolean skipCheckParameter(String url){
        for (String skipCheckTokenPath : gatewayProperty.getCheckSkipParmeterPaths()) {
            PathMatcher matcher = new AntPathMatcher();
            if (matcher.match(skipCheckTokenPath, url)) {
                return true;
            }
        }
        return false;
    }
    
    public boolean checkParameter(String originalBody,String noVerify){
        return (!(VERIFY_VALUE.equals(noVerify))) && StringUtil.isNotEmpty(originalBody);
    }
    
    private boolean checkNeedUserId(String url){
        for (String userIdPath : gatewayProperty.getUserIdPaths()) {
            PathMatcher matcher = new AntPathMatcher();
            if (matcher.match(userIdPath, url)) {
                return true;
            }
        }
        return false;
    }
}
